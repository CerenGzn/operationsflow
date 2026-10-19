package com.operationsflow.messaging.persistence;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.domain.OrderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {
    public record Claim(OrderEvent event,UUID token,int attempt){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public OutboxStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Transactional
    public Optional<Claim> claim(){
        // Expired final attempts are visible failures, not silently stranded leases.
        jdbc.update("""
            update operationsflow.event_outbox o
            set state='FAILED',lease_token=null,lease_until=null,last_error='LEASE_EXHAUSTED'
            from (select event_id from operationsflow.event_outbox
                where state='IN_FLIGHT' and lease_until<=clock_timestamp() and attempts=8
                order by lease_until,event_id for update skip locked limit 100) expired
            where o.event_id=expired.event_id
            """);
        UUID token=UUID.randomUUID();
        var rows=jdbc.query("""
            update operationsflow.event_outbox o set state='IN_FLIGHT',lease_token=?,
                lease_until=clock_timestamp()+interval '30 seconds',attempts=o.attempts+1
            from (select event_id from operationsflow.event_outbox
                where attempts<8 and ((state='PENDING' and next_attempt_at<=clock_timestamp())
                    or (state='IN_FLIGHT' and lease_until<=clock_timestamp()))
                order by next_attempt_at,event_id for update skip locked limit 1) candidate
            where o.event_id=candidate.event_id returning o.*
            """,(rs,n)->{
                try{return new Claim(new OrderEvent(rs.getObject("event_id",UUID.class),OrderEvent.Type.valueOf(rs.getString("event_type")),
                    rs.getInt("schema_version"),rs.getObject("order_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("correlation_id",UUID.class),json.readValue(rs.getString("payload"),OrderEvent.Payload.class)),token,rs.getInt("attempts"));}
                catch(java.io.IOException ex){throw new IllegalStateException("Invalid stored event",ex);}
            },token);
        return rows.stream().findFirst();
    }
    public boolean published(Claim claim){return jdbc.update("update operationsflow.event_outbox set state='PUBLISHED',published_at=clock_timestamp(),lease_token=null,lease_until=null,last_error=null where event_id=? and state='IN_FLIGHT' and lease_token=?",claim.event().eventId(),claim.token())==1;}
    public boolean failed(Claim claim,String code){return jdbc.update("""
        update operationsflow.event_outbox set state=case when attempts=8 then 'FAILED' else 'PENDING' end,
            next_attempt_at=clock_timestamp()+make_interval(secs => power(2,attempts)),
            lease_token=null,lease_until=null,last_error=? where event_id=? and state='IN_FLIGHT' and lease_token=?
        """,code,claim.event().eventId(),claim.token())==1;}
    public Map<String,Long> counts(){var result=new TreeMap<String,Long>();jdbc.query("select state,count(*) n from operationsflow.event_outbox group by state",rs->{result.put(rs.getString("state"),rs.getLong("n"));});return result;}
}

package com.operationsflow.messaging.application;

import java.security.MessageDigest;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.domain.OrderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumer {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public EventConsumer(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Transactional
    public void accept(OrderEvent event){
        final String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(event)));}
        catch(Exception ex){throw new IllegalStateException("Event fingerprint failed",ex);}
        int inserted=jdbc.update("insert into operationsflow.event_inbox(event_id,content_hash) values (?,?) on conflict do nothing",event.eventId(),hash);
        if(inserted==0){
            if(!hash.equals(jdbc.queryForObject("select content_hash from operationsflow.event_inbox where event_id=?",String.class,event.eventId())))
                throw new IllegalArgumentException("Conflicting duplicate event");
            return;
        }
        jdbc.update("insert into operationsflow.order_event_ledger(event_id,order_id,event_type,occurred_at,total_units) values (?,?,?,?,?)",
            event.eventId(),event.orderId(),event.eventType().name(),java.sql.Timestamp.from(event.occurredAt()),event.payload().totalUnits());
    }
}

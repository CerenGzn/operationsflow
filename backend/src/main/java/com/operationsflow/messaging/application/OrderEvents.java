package com.operationsflow.messaging.application;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.domain.OrderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public OrderEvents(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(UUID orderId,OrderEvent.Type type,long units){
        var event=new OrderEvent(UUID.randomUUID(),type,1,orderId,Instant.now(),orderId,new OrderEvent.Payload(units));
        try {
            jdbc.update("insert into operationsflow.event_outbox(event_id,event_type,schema_version,order_id,occurred_at,correlation_id,payload) values (?,?,1,?,?,?,?::jsonb)",
                event.eventId(),type.name(),orderId,java.sql.Timestamp.from(event.occurredAt()),event.correlationId(),json.writeValueAsString(event.payload()));
        } catch(JsonProcessingException ex){throw new IllegalStateException("Cannot encode order event",ex);}
    }
}

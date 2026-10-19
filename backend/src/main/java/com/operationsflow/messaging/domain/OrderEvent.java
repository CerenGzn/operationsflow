package com.operationsflow.messaging.domain;

import java.time.Instant;
import java.util.UUID;

/** Versioned, field-limited wire contract; no Jackson polymorphic type headers. */
public record OrderEvent(UUID eventId, Type eventType, int schemaVersion, UUID orderId,
                         Instant occurredAt, UUID correlationId, Payload payload) {
    public enum Type { ORDER_RESERVED, ORDER_CANCELLED, ORDER_SHIPPED }
    public record Payload(long totalUnits) {
        public Payload { if(totalUnits<=0 || totalUnits>50_000_000_000L) throw new IllegalArgumentException("Invalid units"); }
    }
    public OrderEvent {
        if(eventId==null || eventType==null || schemaVersion!=1 || orderId==null || occurredAt==null || correlationId==null || payload==null)
            throw new IllegalArgumentException("Invalid event contract");
    }
}

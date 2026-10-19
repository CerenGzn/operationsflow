package com.operationsflow.messaging.application;

import com.operationsflow.messaging.infrastructure.ConfirmedSender;
import com.operationsflow.messaging.persistence.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("messaging & !demo")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxStore store;
    private final ConfirmedSender sender;
    public OutboxPublisher(OutboxStore store, ConfirmedSender sender) { this.store = store; this.sender = sender; }
    public boolean publishOne() {
        var next = store.claim();
        if (next.isEmpty()) return false;
        var claim = next.get();
        // Claim transaction has committed. No database locks are held during broker I/O.
        var result = sender.send(claim.event());
        boolean current = result == ConfirmedSender.Result.CONFIRMED
            ? store.published(claim) : store.failed(claim, result.name());
        log.info("outbox event_id={} result={} attempt={} lease_current={}",
            claim.event().eventId(), result, claim.attempt(), current);
        return true;
    }
}

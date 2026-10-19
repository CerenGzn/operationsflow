package com.operationsflow.messaging.api;

import java.util.Map;
import com.operationsflow.messaging.persistence.OutboxStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("messaging & !demo")
@RequestMapping("/api/v1/messaging")
public class MessagingStatusController {
    private final OutboxStore outbox;
    private final JdbcTemplate jdbc;
    public MessagingStatusController(OutboxStore outbox, JdbcTemplate jdbc) { this.outbox = outbox; this.jdbc = jdbc; }
    public record Status(Map<String, Long> outbox, long processedEvents) {}
    @GetMapping("/status")
    @Operation(summary = "Administrator-only outbox state and processed-event counts; no event payloads")
    public Status status() {
        return new Status(outbox.counts(), jdbc.queryForObject("select count(*) from operationsflow.order_event_ledger", Long.class));
    }
}

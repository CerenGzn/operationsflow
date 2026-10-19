package com.operationsflow;

import java.util.*;
import com.operationsflow.messaging.domain.OrderEvent;
import com.operationsflow.messaging.persistence.OutboxStore;
import com.operationsflow.orders.api.OrderContracts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OutboxIT extends MessagingTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void committedTransitionsAndConcurrentRetriesProduceOneEventPerTransition() throws Exception {
        var f = fixture(); var key = UUID.randomUUID();
        var replies = concurrent(4, i -> orders.create(key,request(f)));
        assertThat(replies.stream().map(OrderContracts.View::id).distinct()).hasSize(1);
        var order = replies.getFirst(); assertThat(count("event_outbox")).isEqualTo(1);
        concurrent(4, i -> orders.cancel(order.id(),new OrderContracts.Cancel("Synthetic cancellation")));
        assertThat(count("event_outbox")).isEqualTo(2);
        assertThat(orders.create(key,request(f))).isEqualTo(order);
        assertThat(count("event_outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved from operationsflow.inventory_balance where sku_id=? and warehouse_id=?",Long.class,f.sku(),f.warehouse())).isZero();
    }
    @Test void dispatchRetriesProduceOneShippedEvent() throws Exception {
        var order = create(); ready(order.id());
        concurrent(4, i -> fulfillment.dispatch(order.id()));
        assertThat(jdbc.queryForList("select event_type from operationsflow.event_outbox",String.class))
            .containsExactlyInAnyOrder("ORDER_RESERVED","ORDER_SHIPPED");
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where reservation_id=? and type='FULFILLMENT'",Long.class,order.reservationId())).isEqualTo(1);
    }
    @Test void orderAndCancellationRollbackDoNotLeakEvents() {
        var f = fixture(); long before = count("sales_order");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            orders.create(UUID.randomUUID(),request(f)); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("sales_order")).isEqualTo(before); assertThat(count("event_outbox")).isZero();
        var order = orders.create(UUID.randomUUID(),request(f));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            orders.cancel(order.id(),new OrderContracts.Cancel("Synthetic rollback")); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("event_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reserved from operationsflow.inventory_balance where sku_id=?",Long.class,f.sku())).isEqualTo(3);
    }
    @Test void dispatchRollbackPreservesStockAndOutbox() {
        var order = create(); ready(order.id());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            fulfillment.dispatch(order.id()); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("event_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where reservation_id=? and type='FULFILLMENT'",Long.class,order.reservationId())).isZero();
    }
    @Test void insufficientStockAndMissingTransactionCannotEmitEvents() {
        var f = fixture();
        assertThatThrownBy(() -> orders.create(UUID.randomUUID(),new OrderContracts.Create(f.customer(),List.of(new OrderContracts.Line(f.sku(),11)))))
            .isInstanceOf(com.operationsflow.shared.domain.BusinessException.class);
        assertThat(count("event_outbox")).isZero();
        assertThatThrownBy(() -> events.record(UUID.randomUUID(),OrderEvent.Type.ORDER_RESERVED,1))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void competingWorkersClaimDistinctEventsAndSkipLockedRows() throws Exception {
        create(); create(); create(); create();
        var claims = concurrent(4, i -> outbox.claim().orElseThrow());
        assertThat(claims.stream().map(c -> c.event().eventId()).distinct()).hasSize(4);
        assertThat(outbox.claim()).isEmpty();
        assertThat(outbox.counts()).containsEntry("IN_FLIGHT",4L);
    }
    @Test void expiredLeaseRecoversAndRejectsStaleWorkerAcknowledgment() {
        create(); var first = outbox.claim().orElseThrow();
        jdbc.update("update operationsflow.event_outbox set lease_until=now()-interval '1 second' where event_id=?",first.event().eventId());
        var recovered = outbox.claim().orElseThrow();
        assertThat(recovered.event()).isEqualTo(first.event()); assertThat(recovered.token()).isNotEqualTo(first.token());
        assertThat(outbox.published(first)).isFalse(); assertThat(outbox.failed(first,"UNCONFIRMED")).isFalse();
        assertThat(outbox.published(recovered)).isTrue(); assertThat(state(first.event().eventId())).isEqualTo("PUBLISHED");
    }
    @Test void boundedBackoffEndsInVisibleFailure() {
        create(); UUID event = null;
        for (int i=1;i<=8;i++) {
            var claim = outbox.claim().orElseThrow(); event = claim.event().eventId(); assertThat(claim.attempt()).isEqualTo(i);
            assertThat(outbox.failed(claim,"UNCONFIRMED")).isTrue(); assertThat(outbox.claim()).isEmpty();
            assertThat(jdbc.queryForObject("select next_attempt_at>clock_timestamp() from operationsflow.event_outbox where event_id=?",Boolean.class,event)).isTrue();
            jdbc.update("update operationsflow.event_outbox set next_attempt_at=now()-interval '1 second' where event_id=?",event);
        }
        assertThat(state(event)).isEqualTo("FAILED"); assertThat(outbox.claim()).isEmpty();
    }
    @Test void expiredFinalAttemptIsNotStranded() {
        create(); var claim = outbox.claim().orElseThrow();
        jdbc.update("update operationsflow.event_outbox set attempts=8,lease_until=now()-interval '1 second' where event_id=?",claim.event().eventId());
        assertThat(outbox.claim()).isEmpty(); assertThat(state(claim.event().eventId())).isEqualTo("FAILED");
    }
    @Test void concurrentDuplicatesOnlyWriteOneLedgerEntryAndConflictsAreRejected() throws Exception {
        create(); var event = outbox.claim().orElseThrow().event();
        concurrent(6, i -> { consumer.accept(event); return true; });
        assertThat(count("event_inbox")).isEqualTo(1); assertThat(count("order_event_ledger")).isEqualTo(1);
        var conflict = new OrderEvent(event.eventId(),event.eventType(),1,event.orderId(),event.occurredAt(),event.correlationId(),new OrderEvent.Payload(4));
        assertThatThrownBy(() -> consumer.accept(conflict)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("order_event_ledger")).isEqualTo(1);
    }
    @Test void consumerRollbackAllowsSafeRetryWithoutChangingBusinessTruth() {
        var order = create(); var event = outbox.claim().orElseThrow().event();
        long movements = count("inventory_movement"), audits = count("audit_event");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            consumer.accept(event); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("event_inbox")).isZero(); assertThat(count("order_event_ledger")).isZero();
        consumer.accept(event); consumer.accept(event);
        assertThat(count("order_event_ledger")).isEqualTo(1); assertThat(count("inventory_movement")).isEqualTo(movements);
        assertThat(count("audit_event")).isEqualTo(audits); assertThat(orders.get(order.id())).isEqualTo(order);
    }
    @Test void databaseRejectsMalformedPayloadAndDuplicateTransition() {
        create(); var claim = outbox.claim().orElseThrow();
        for (String payload : List.of("{}","{\"totalUnits\":0}","{\"totalUnits\":1,\"address\":\"forbidden\"}","{\"totalUnits\":1.5}"))
            assertThatThrownBy(() -> jdbc.update("update operationsflow.event_outbox set payload=?::jsonb where event_id=?",payload,claim.event().eventId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            events.record(claim.event().orderId(),claim.event().eventType(),3); return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }
}

package com.operationsflow;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.inventory.application.*;
import com.operationsflow.inventory.api.InventoryContracts.*;
import com.operationsflow.inventory.api.WarehouseContracts;
import com.operationsflow.inventory.domain.ReservationState;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InventoryIT extends AuthenticatedTestSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired InventoryService inventory;
    @Autowired InventoryQueries queries;
    @Autowired ProductService products;
    @Autowired WarehouseService warehouses;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    record Fixture(UUID product, UUID sku, UUID warehouse) {}
    private Fixture fixture() {
        var p = UUID.randomUUID(); var s = UUID.randomUUID(); var w = UUID.randomUUID();
        jdbc.update("insert into operationsflow.product(id,name,description,status,created_at,updated_at) values (?,'Synthetic item','','ACTIVE',now(),now())", p);
        jdbc.update("insert into operationsflow.sku(id,product_id,code) values (?,?,?)", s, p, "SKU-" + s.toString().toUpperCase(Locale.ROOT));
        jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic warehouse','Example street 1','Berlin','10115','DE','ACTIVE',now(),now())", w, "W-" + w.toString().substring(0, 20).toUpperCase(Locale.ROOT));
        return new Fixture(p, s, w);
    }
    private Receipt receipt(Fixture f, long qty) { return new Receipt(f.warehouse(), f.sku(), qty, "Synthetic receipt"); }
    private Reserve reservation(Fixture f, long qty) { return new Reserve("test:" + UUID.randomUUID(), List.of(new Line(f.warehouse(), f.sku(), qty)), "Synthetic reservation"); }
    private ReservationView reserve(Fixture f, long qty) { return inventory.reserve(UUID.randomUUID(), reservation(f, qty)); }
    private BalanceView balance(Fixture f) { return queries.balances(f.warehouse(), f.sku(), 0, 20).content().getFirst(); }
    private void reconcile(Fixture f) {
        var balance = balance(f);
        var sums = jdbc.queryForMap("select coalesce(sum(on_hand_delta),0)::bigint h, coalesce(sum(reserved_delta),0)::bigint r from operationsflow.inventory_movement where warehouse_id = ? and sku_id = ?", f.warehouse(), f.sku());
        assertThat(balance.onHand()).isEqualTo(((Number) sums.get("h")).longValue());
        assertThat(balance.reserved()).isEqualTo(((Number) sums.get("r")).longValue());
        assertThat(balance.available()).isEqualTo(balance.onHand() - balance.reserved());
        Long active = jdbc.queryForObject("select coalesce(sum(l.quantity),0)::bigint from operationsflow.stock_reservation_line l join operationsflow.stock_reservation r on r.id = l.reservation_id where r.state = 'ACTIVE' and l.warehouse_id = ? and l.sku_id = ?", Long.class, f.warehouse(), f.sku());
        assertThat(balance.reserved()).isEqualTo(active);
    }
    private <T> List<T> concurrent(int count, IntFunction<T> action) throws Exception {
        var caller = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(caller); try { ready.countDown(); start.await(); return action.apply(index); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS); start.countDown(); assertThat(allReady).isTrue();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void twentyConcurrentReservationsCannotOversellTenUnits() throws Exception {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 10));
        var outcomes = concurrent(20, i -> {
            try { reserve(f, 1); return "RESERVED"; }
            catch (BusinessException ex) { assertThat(ex.code()).isEqualTo("INSUFFICIENT_STOCK"); return "REJECTED"; }
        });
        assertThat(outcomes.stream().filter("RESERVED"::equals).count()).isEqualTo(10);
        assertThat(balance(f).reserved()).isEqualTo(10); assertThat(balance(f).available()).isZero(); reconcile(f);
    }
    @Test void concurrentReleaseHasExactlyOneEffect() throws Exception {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 8)); var r = reserve(f, 8);
        var outcomes = concurrent(12, i -> inventory.release(r.id(), new Terminal("Synthetic cancellation")));
        assertThat(outcomes).allMatch(v -> v.state() == ReservationState.RELEASED);
        assertThat(balance(f).onHand()).isEqualTo(8); assertThat(balance(f).reserved()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where reservation_id = ? and type = 'RELEASE'", Long.class, r.id())).isEqualTo(1L);
        reconcile(f);
    }
    @Test void reservationRacingReleaseKeepsLedgerAndActiveReservationsConsistent() throws Exception {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 10)); var original = reserve(f, 5);
        var result = concurrent(2, i -> {
            if (i == 0) { inventory.release(original.id(), new Terminal("Release original")); return "RELEASED"; }
            try { reserve(f, 10); return "RESERVED"; }
            catch (BusinessException ex) { assertThat(ex.code()).isEqualTo("INSUFFICIENT_STOCK"); return "REJECTED"; }
        });
        assertThat(balance(f).reserved()).isEqualTo(result.contains("RESERVED") ? 10 : 0); reconcile(f);
    }
    @Test void releaseAndConsumptionCannotBothWin() throws Exception {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 9)); var r = reserve(f, 9);
        var outcomes = concurrent(2, i -> {
            try { return (i == 0 ? inventory.release(r.id(), new Terminal("Cancel")) : inventory.consume(r.id(), new Terminal("Fulfill"))).state().name(); }
            catch (BusinessException ex) { assertThat(ex.code()).isEqualTo("INVALID_TRANSITION"); return "REJECTED"; }
        });
        assertThat(outcomes).contains("REJECTED"); assertThat(balance(f).reserved()).isZero();
        assertThat(balance(f).onHand()).isEqualTo(outcomes.contains("CONSUMED") ? 0 : 9); reconcile(f);
    }
    @Test void concurrentIdempotentReceiptsReplayOneMovementAndOriginalSnapshot() throws Exception {
        var f = fixture(); var key = UUID.randomUUID(); var request = receipt(f, 5);
        var replies = concurrent(10, i -> inventory.receive(key, request));
        assertThat(replies.stream().map(MovementView::id).distinct()).hasSize(1);
        inventory.receive(UUID.randomUUID(), receipt(f, 7));
        assertThat(inventory.receive(key, request).onHandAfter()).isEqualTo(5);
        assertThat(balance(f).onHand()).isEqualTo(12);
        assertThatThrownBy(() -> inventory.receive(key, receipt(f, 6))).isInstanceOf(BusinessException.class).hasMessageContaining("idempotency");
        assertThatThrownBy(() -> inventory.adjust(key, new Adjustment(f.warehouse(), f.sku(), 1, "Correction"))).isInstanceOf(BusinessException.class);
        reconcile(f);
    }
    @Test void canonicalReservationRetriesAndUniqueOwnerPreventDoubleReservation() {
        var a = fixture(); var b = fixture(); inventory.receive(UUID.randomUUID(), receipt(a, 10)); inventory.receive(UUID.randomUUID(), receipt(b, 10));
        var lines = List.of(new Line(a.warehouse(), a.sku(), 2), new Line(b.warehouse(), b.sku(), 3));
        var key = UUID.randomUUID(); var owner = "test:" + UUID.randomUUID();
        var first = inventory.reserve(key, new Reserve(owner, lines, "Reserve both"));
        var retry = inventory.reserve(key, new Reserve(owner, List.of(lines.get(1), lines.get(0)), "Reserve both"));
        assertThat(retry).isEqualTo(first);
        assertThatThrownBy(() -> inventory.reserve(UUID.randomUUID(), new Reserve(owner, lines, "Reserve both"))).isInstanceOf(DataIntegrityViolationException.class);
        inventory.release(first.id(), new Terminal("Cancel"));
        assertThat(inventory.reserve(key, new Reserve(owner, lines, "Reserve both")).state()).isEqualTo(ReservationState.ACTIVE);
        assertThat(queries.reservation(first.id()).state()).isEqualTo(ReservationState.RELEASED); reconcile(a); reconcile(b);
    }
    @Test void reversedOverlappingRequestsUseOneLockOrder() throws Exception {
        var a = fixture(); var b = fixture(); inventory.receive(UUID.randomUUID(), receipt(a, 10)); inventory.receive(UUID.randomUUID(), receipt(b, 10));
        var lineA = new Line(a.warehouse(), a.sku(), 2); var lineB = new Line(b.warehouse(), b.sku(), 2);
        var result = concurrent(2, i -> inventory.reserve(UUID.randomUUID(), new Reserve("test:" + UUID.randomUUID(), i == 0 ? List.of(lineA, lineB) : List.of(lineB, lineA), "Overlapping request")));
        assertThat(result).hasSize(2); assertThat(balance(a).reserved()).isEqualTo(4); assertThat(balance(b).reserved()).isEqualTo(4); reconcile(a); reconcile(b);
    }
    @Test void insufficientSecondLineRollsBackEarlierLineOwnerKeyAndMovement() {
        var fixtures = new ArrayList<>(List.of(fixture(), fixture())); fixtures.sort(Comparator.comparing(f -> f.warehouse().toString()));
        var first = fixtures.get(0); var second = fixtures.get(1); inventory.receive(UUID.randomUUID(), receipt(first, 5));
        var owner = "rollback:" + UUID.randomUUID(); var key = UUID.randomUUID();
        var request = new Reserve(owner, List.of(new Line(first.warehouse(), first.sku(), 2), new Line(second.warehouse(), second.sku(), 1)), "Atomic batch");
        assertThatThrownBy(() -> inventory.reserve(key, request)).isInstanceOf(BusinessException.class);
        assertThat(balance(first).reserved()).isZero(); assertThat(queries.balances(second.warehouse(), second.sku(), 0, 20).content()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.stock_reservation where owner_reference = ?", Long.class, owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.stock_operation where id = ?", Long.class, key)).isZero();
        inventory.receive(UUID.randomUUID(), receipt(second, 1)); inventory.reserve(key, request); reconcile(first); reconcile(second);
    }
    @Test void outerBusinessTransactionCanRollBackInventoryForFutureOrders() {
        var f = fixture(); var key = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            inventory.receive(key, receipt(f, 4)); throw new IllegalStateException("Synthetic order failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queries.balances(f.warehouse(), f.sku(), 0, 20).content()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.stock_operation where id = ?", Long.class, key)).isZero();
        inventory.receive(key, receipt(f, 4)); reconcile(f);
    }
    @Test void invalidAdjustmentsDoNotChangeHistoryAndValidAdjustmentIsIdempotent() {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 10)); reserve(f, 8);
        long count = queries.movements(f.warehouse(), f.sku(), 0, 20).totalElements();
        for (long delta : new long[] { 0, -3, -11 }) {
            assertThatThrownBy(() -> inventory.adjust(UUID.randomUUID(), new Adjustment(f.warehouse(), f.sku(), delta, "Count correction"))).isInstanceOf(BusinessException.class);
        }
        assertThat(queries.movements(f.warehouse(), f.sku(), 0, 20).totalElements()).isEqualTo(count);
        var key = UUID.randomUUID(); var request = new Adjustment(f.warehouse(), f.sku(), -2, "Correct count");
        assertThat(inventory.adjust(key, request)).isEqualTo(inventory.adjust(key, request));
        assertThat(balance(f).onHand()).isEqualTo(8); reconcile(f);
    }
    @Test void databaseConstraintsAndAppendOnlyHistoryRejectInvalidWrites() {
        var f = fixture(); var movement = inventory.receive(UUID.randomUUID(), receipt(f, 5));
        for (var counters : List.of(new long[] {-1, 0}, new long[] {5, -1}, new long[] {5, 6})) {
            assertThatThrownBy(() -> jdbc.update("update operationsflow.inventory_balance set on_hand = ?, reserved = ? where warehouse_id = ? and sku_id = ?", counters[0], counters[1], f.warehouse(), f.sku())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("delete from operationsflow.inventory_movement where id = ?", movement.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update operationsflow.inventory_movement set reason = 'Changed' where id = ?", movement.id())).isInstanceOf(DataIntegrityViolationException.class);
        var r = reserve(f, 2); inventory.release(r.id(), new Terminal("Cancel"));
        assertThatThrownBy(() -> jdbc.update("update operationsflow.stock_reservation set state = 'ACTIVE' where id = ?", r.id())).isInstanceOf(DataIntegrityViolationException.class); reconcile(f);
    }
    @Test void inactiveReferencesRejectChangesAndStockBlocksDeactivation() {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 5)); var r = reserve(f, 2);
        assertThatThrownBy(() -> products.deactivate(f.product(), new ProductContracts.Deactivate(0L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> warehouses.deactivate(f.warehouse(), new WarehouseContracts.Deactivate(0L))).isInstanceOf(BusinessException.class);
        // Simulate out-of-band administrative status corruption; even release must fail closed.
        jdbc.update("update operationsflow.product set status = 'INACTIVE' where id = ?", f.product());
        assertThatThrownBy(() -> inventory.release(r.id(), new Terminal("Cancel"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inventory.receive(UUID.randomUUID(), receipt(f, 1))).isInstanceOf(BusinessException.class);
        jdbc.update("update operationsflow.product set status = 'ACTIVE' where id = ?", f.product());
        inventory.release(r.id(), new Terminal("Cancel")); inventory.adjust(UUID.randomUUID(), new Adjustment(f.warehouse(), f.sku(), -5, "Remove stock"));
        products.deactivate(f.product(), new ProductContracts.Deactivate(0L));
        assertThatThrownBy(() -> reserve(f, 1)).isInstanceOf(BusinessException.class);
        var other = fixture(); warehouses.deactivate(other.warehouse(), new WarehouseContracts.Deactivate(0L));
        assertThatThrownBy(() -> inventory.receive(UUID.randomUUID(), receipt(other, 1))).isInstanceOf(BusinessException.class); reconcile(f);
    }
    @Test void deactivationRacingReceiptCannotLeaveStockOnInactiveProduct() throws Exception {
        var f = fixture();
        var outcomes = concurrent(2, i -> {
            try {
                if (i == 0) products.deactivate(f.product(), new ProductContracts.Deactivate(0L)); else inventory.receive(UUID.randomUUID(), receipt(f, 3));
                return "OK";
            } catch (BusinessException ex) { assertThat(ex.code()).isIn("STOCK_REMAINS", "INACTIVE_RECORD"); return "REJECTED"; }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "REJECTED");
        String state = jdbc.queryForObject("select status from operationsflow.product where id = ?", String.class, f.product());
        if (state.equals("ACTIVE")) { assertThat(balance(f).onHand()).isEqualTo(3); reconcile(f); }
        else assertThat(queries.balances(f.warehouse(), f.sku(), 0, 20).content()).isEmpty();
    }
    @Test void consumptionIsIdempotentAndHistoryExposesTruthfulActorAndReason() {
        var f = fixture(); inventory.receive(UUID.randomUUID(), receipt(f, 7)); var r = reserve(f, 4);
        var first = inventory.consume(r.id(), new Terminal("Synthetic shipment"));
        assertThat(inventory.consume(r.id(), new Terminal("Retry shipment"))).isEqualTo(first);
        assertThat(balance(f).onHand()).isEqualTo(3); assertThat(balance(f).reserved()).isZero();
        var history = queries.movements(f.warehouse(), f.sku(), 0, 20);
        assertThat(history.content()).extracting(MovementView::type).containsExactly("FULFILLMENT", "RESERVATION", "RECEIPT");
        assertThat(history.content().getFirst().reason()).isEqualTo("Synthetic shipment");
        assertThat(history.content()).allMatch(m -> m.actor().equals("user:"+TEST_ADMIN)); reconcile(f);
    }
    @Test void httpContractsValidateKeysBodiesFiltersAndUnknownReferences() throws Exception {
        var f = fixture(); String payload = json.writeValueAsString(receipt(f, 3)); var key = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/inventory/receipts").contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/inventory/receipts").header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(receipt(f, -1))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/inventory/receipts").header("Idempotency-Key", key).contentType("application/json").content(payload))
            .andExpect(status().isCreated()).andExpect(header().exists("Location"));
        mvc.perform(post("/api/v1/inventory/receipts").header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(receipt(f, 4))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(get("/api/v1/inventory/balances").param("warehouseId", f.warehouse().toString()).param("skuId", f.sku().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].available").value(3));
        mvc.perform(get("/api/v1/inventory/balances").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/inventory/reservations/" + UUID.randomUUID())).andExpect(status().isNotFound());
        var unknown = new Receipt(f.warehouse(), UUID.randomUUID(), 1, "Unknown SKU");
        mvc.perform(post("/api/v1/inventory/receipts").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(unknown))).andExpect(status().isNotFound());
        var dup = new Reserve("test:duplicate", List.of(new Line(f.warehouse(), f.sku(), 1), new Line(f.warehouse(), f.sku(), 1)), "Duplicate lines");
        mvc.perform(post("/api/v1/inventory/reservations").header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(dup))).andExpect(status().isBadRequest());
    }
}

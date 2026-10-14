package com.operationsflow.inventory.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.inventory.domain.*;
import com.operationsflow.inventory.api.InventoryContracts.*;
import com.operationsflow.shared.domain.BusinessException;

/** Explicit SQL makes lock acquisition order and ledger writes reviewable. */
@Repository
public class InventoryStore {
    private final com.operationsflow.audit.application.AuditService audit;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public InventoryStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, com.operationsflow.audit.application.AuditService audit) {
        this.audit = audit; this.jdbc = jdbc; this.named = named; }
    public void limitLockWait() { jdbc.execute("set local lock_timeout = '3s'"); }
    public record Operation(String operation, String hash, String response) {}
    public Operation claim(UUID key, String operation, String hash) {
        jdbc.update("insert into operationsflow.stock_operation(id, operation, request_hash) values (?, ?, ?) on conflict do nothing", key, operation, hash);
        return jdbc.queryForObject("select operation, request_hash, response::text from operationsflow.stock_operation where id = ? for update",
            (rs, n) -> new Operation(rs.getString(1), rs.getString(2), rs.getString(3)), key);
    }
    public void complete(UUID key, String response) {
        jdbc.update("update operationsflow.stock_operation set response = cast(? as jsonb) where id = ?", response, key);
    }
    public void lockWarehouses(Collection<UUID> ids) {
        var unique = new HashSet<>(ids);
        var statuses = named.query("select status from operationsflow.warehouse where id in (:ids) order by id for update",
            Map.of("ids", unique), (rs, n) -> rs.getString(1));
        if (statuses.size() != unique.size()) throw new BusinessException("NOT_FOUND", "One or more warehouses do not exist.");
        if (statuses.stream().anyMatch(s -> !s.equals("ACTIVE"))) throw new BusinessException("INACTIVE_RECORD", "Inventory cannot change in an inactive warehouse.");
    }
    public StockBalance lockBalance(StockLine line) {
        jdbc.update("insert into operationsflow.inventory_balance(warehouse_id, sku_id) values (?, ?) on conflict do nothing", line.warehouseId(), line.skuId());
        return jdbc.queryForObject("select on_hand, reserved from operationsflow.inventory_balance where warehouse_id = ? and sku_id = ? for update",
            (rs, n) -> new StockBalance(rs.getLong(1), rs.getLong(2)), line.warehouseId(), line.skuId());
    }
    public UUID write(StockLine line, StockBalance before, StockBalance after, String type, UUID reservationId, UUID operationId, String reason) {
        jdbc.update("update operationsflow.inventory_balance set on_hand = ?, reserved = ?, updated_at = clock_timestamp() where warehouse_id = ? and sku_id = ?",
            after.onHand(), after.reserved(), line.warehouseId(), line.skuId());
        UUID movementId = UUID.randomUUID();
        jdbc.update("""
            insert into operationsflow.inventory_movement(id, warehouse_id, sku_id, type, on_hand_delta, reserved_delta,
                on_hand_after, reserved_after, reservation_id, operation_id, reason, actor)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, movementId, line.warehouseId(), line.skuId(), type, after.onHand() - before.onHand(), after.reserved() - before.reserved(),
            after.onHand(), after.reserved(), reservationId, operationId, reason, com.operationsflow.security.application.CurrentActor.label());
        audit.record("INVENTORY_"+type,movementId);
        return movementId;
    }
    public void insertReservation(UUID id, String ownerReference) {
        jdbc.update("insert into operationsflow.stock_reservation(id, owner_reference, state) values (?, ?, 'ACTIVE')", id, ownerReference);
    }
    public void insertOrderReservation(UUID id, UUID orderId) {
        jdbc.update("insert into operationsflow.stock_reservation(id, owner_reference, state, order_id) values (?, ?, 'ACTIVE', ?)", id, "order:" + orderId, orderId);
    }
    public UUID reservationOrder(UUID id) {
        return jdbc.queryForObject("select order_id from operationsflow.stock_reservation where id = ?", UUID.class, id);
    }
    public record WarehouseOption(UUID id, String code) {}
    public List<WarehouseOption> lockActiveWarehouses() {
        return jdbc.query("select id, code from operationsflow.warehouse where status = 'ACTIVE' order by id for update",
            (rs, n) -> new WarehouseOption(rs.getObject(1, UUID.class), rs.getString(2)));
    }
    public List<StockLine> lockAvailable(Collection<UUID> skus, Collection<UUID> warehouses) {
        if (warehouses.isEmpty()) return List.of();
        return named.query("select warehouse_id, sku_id, on_hand - reserved available from operationsflow.inventory_balance where sku_id in (:skus) and warehouse_id in (:warehouses) order by warehouse_id, sku_id for update",
            Map.of("skus", skus, "warehouses", warehouses), (rs, n) -> new StockLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)));
    }
    public void insertLine(UUID reservationId, StockLine line) {
        jdbc.update("insert into operationsflow.stock_reservation_line(reservation_id, warehouse_id, sku_id, quantity) values (?, ?, ?, ?)",
            reservationId, line.warehouseId(), line.skuId(), line.quantity());
    }
    public ReservationState lockReservation(UUID id) {
        var states = jdbc.query("select state from operationsflow.stock_reservation where id = ? for update",
            (rs, n) -> ReservationState.valueOf(rs.getString(1)), id);
        if (states.isEmpty()) throw new BusinessException("NOT_FOUND", "Reservation not found.");
        return states.getFirst();
    }
    public List<StockLine> lines(UUID id) {
        return jdbc.query("select warehouse_id, sku_id, quantity from operationsflow.stock_reservation_line where reservation_id = ? order by warehouse_id, sku_id",
            (rs, n) -> new StockLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)), id);
    }
    public void transition(UUID id, ReservationState target) {
        jdbc.update("update operationsflow.stock_reservation set state = ?, updated_at = clock_timestamp() where id = ?", target.name(), id);
        audit.record("RESERVATION_"+target.name(),id);
    }
    public boolean hasWarehouseStock(UUID warehouseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from operationsflow.inventory_balance where warehouse_id = ? and (on_hand > 0 or reserved > 0))", Boolean.class, warehouseId));
    }
    public boolean hasSkuStock(Collection<UUID> skuIds) {
        return !skuIds.isEmpty() && Boolean.TRUE.equals(named.queryForObject("select exists(select 1 from operationsflow.inventory_balance where sku_id in (:ids) and (on_hand > 0 or reserved > 0))", Map.of("ids", skuIds), Boolean.class));
    }
    public static MovementView movement(ResultSet rs, int n) throws SQLException {
        return new MovementView(rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class), rs.getObject("sku_id", UUID.class),
            rs.getString("warehouse_code"), rs.getString("sku_code"), rs.getString("type"), rs.getLong("on_hand_delta"), rs.getLong("reserved_delta"),
            rs.getLong("on_hand_after"), rs.getLong("reserved_after"), rs.getObject("reservation_id", UUID.class), rs.getObject("operation_id", UUID.class),
            rs.getString("reason"), rs.getString("actor"), rs.getTimestamp("created_at").toInstant());
    }
}

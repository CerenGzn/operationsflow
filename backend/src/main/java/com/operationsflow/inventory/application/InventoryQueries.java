package com.operationsflow.inventory.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.operationsflow.inventory.api.InventoryContracts.*;
import com.operationsflow.inventory.domain.ReservationState;
import com.operationsflow.inventory.persistence.InventoryStore;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.BusinessException;

/** Read-only joined projections; mutations access catalog via its public service. */
@Service
@Transactional(readOnly = true)
public class InventoryQueries {
    private final NamedParameterJdbcTemplate jdbc;
    public InventoryQueries(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Map<String, Object> parameters(UUID warehouseId, UUID skuId, int page, int size) {
        ListQuery.page(page, size, "id", "asc", Set.of("id"));
        var values = new HashMap<String, Object>();
        values.put("warehouse", warehouseId); values.put("sku", skuId); values.put("limit", size); values.put("offset", (long) page * size);
        return values;
    }
    private String filter(UUID warehouseId, UUID skuId, String alias) {
        return " where true" + (warehouseId == null ? "" : " and " + alias + ".warehouse_id = :warehouse") +
            (skuId == null ? "" : " and " + alias + ".sku_id = :sku");
    }
    private <T> PageResponse<T> page(List<T> rows, long total, int page, int size) {
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }
    public PageResponse<BalanceView> balances(UUID warehouseId, UUID skuId, int page, int size) {
        var params = parameters(warehouseId, skuId, page, size); var where = filter(warehouseId, skuId, "b");
        long total = jdbc.queryForObject("select count(*) from operationsflow.inventory_balance b" + where, params, Long.class);
        var rows = jdbc.query("""
            select b.*, w.code warehouse_code, s.code sku_code, p.name product_name
            from operationsflow.inventory_balance b join operationsflow.warehouse w on w.id = b.warehouse_id
            join operationsflow.sku s on s.id = b.sku_id join operationsflow.product p on p.id = s.product_id
            """ + where + " order by w.code, s.code, b.warehouse_id, b.sku_id limit :limit offset :offset", params,
            (rs, n) -> new BalanceView(rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_code"), rs.getObject("sku_id", UUID.class),
                rs.getString("sku_code"), rs.getString("product_name"), rs.getLong("on_hand"), rs.getLong("reserved"),
                rs.getLong("on_hand") - rs.getLong("reserved"), rs.getTimestamp("updated_at").toInstant()));
        return page(rows, total, page, size);
    }
    private static final String MOVEMENT_SELECT = """
        select m.*, w.code warehouse_code, s.code sku_code from operationsflow.inventory_movement m
        join operationsflow.warehouse w on w.id = m.warehouse_id join operationsflow.sku s on s.id = m.sku_id
        """;
    public MovementView movement(UUID id) {
        var rows = jdbc.query(MOVEMENT_SELECT + " where m.id = :id", Map.of("id", id), InventoryStore::movement);
        if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "Movement not found.");
        return rows.getFirst();
    }
    public PageResponse<MovementView> movements(UUID warehouseId, UUID skuId, int page, int size) {
        var params = parameters(warehouseId, skuId, page, size); var where = filter(warehouseId, skuId, "m");
        long total = jdbc.queryForObject("select count(*) from operationsflow.inventory_movement m" + where, params, Long.class);
        return page(jdbc.query(MOVEMENT_SELECT + where + " order by m.created_at desc, m.id desc limit :limit offset :offset", params, InventoryStore::movement), total, page, size);
    }
    private List<ReservationView> reservationRows(String where, Map<String, ?> params) {
        var headers = jdbc.query("select * from operationsflow.stock_reservation " + where, params,
            (rs, n) -> new ReservationView(rs.getObject("id", UUID.class), rs.getString("owner_reference"), rs.getObject("order_id", UUID.class), ReservationState.valueOf(rs.getString("state")), List.of(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()));
        if (headers.isEmpty()) return headers;
        record Row(UUID reservation, Line line) {}
        var grouped = jdbc.query("select * from operationsflow.stock_reservation_line where reservation_id in (:ids) order by warehouse_id, sku_id",
            Map.of("ids", headers.stream().map(ReservationView::id).toList()),
            (rs, n) -> new Row(rs.getObject("reservation_id", UUID.class), new Line(rs.getObject("warehouse_id", UUID.class), rs.getObject("sku_id", UUID.class), rs.getLong("quantity"))))
            .stream().collect(Collectors.groupingBy(Row::reservation, Collectors.mapping(Row::line, Collectors.toList())));
        return headers.stream().map(h -> new ReservationView(h.id(), h.ownerReference(), h.orderId(), h.state(), grouped.getOrDefault(h.id(), List.of()), h.createdAt(), h.updatedAt())).toList();
    }
    public ReservationView reservation(UUID id) {
        var rows = reservationRows("where id = :id", Map.of("id", id));
        if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "Reservation not found.");
        return rows.getFirst();
    }
    public PageResponse<ReservationView> reservations(ReservationState state, int page, int size) {
        var params = parameters(null, null, page, size); params.put("state", state == null ? null : state.name());
        var where = state == null ? "" : " where state = :state";
        long total = jdbc.queryForObject("select count(*) from operationsflow.stock_reservation" + where, params, Long.class);
        return page(reservationRows(where + " order by created_at desc, id desc limit :limit offset :offset", params), total, page, size);
    }
}

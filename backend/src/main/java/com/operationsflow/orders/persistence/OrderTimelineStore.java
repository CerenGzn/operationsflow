package com.operationsflow.orders.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.shared.domain.BusinessException;

@Repository
public class OrderTimelineStore {
    private final JdbcTemplate jdbc;
    public OrderTimelineStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Facts(UUID id, OrderStatus status, Instant createdAt, Instant pickingAt,
        long tasks, long packages, Instant packedAt, long shipments, Instant preparedAt,
        long dispatched, Instant dispatchedAt, Instant cancelledAt) {}

    /** One SQL statement gives one PostgreSQL snapshot, including concurrently committed dispatch/cancellation. */
    public Facts get(UUID id) {
        return jdbc.query("""
            with tasks as (select id from operationsflow.fulfillment_task where order_id = ?),
            totals as (
                select count(t.id) as tasks, count(p.id) as packages, max(p.packed_at) as packed_at,
                    count(s.id) as shipments, max(s.created_at) as prepared_at,
                    count(s.dispatched_at) as dispatched, max(s.dispatched_at) as dispatched_at
                from tasks t
                left join operationsflow.fulfillment_package p on p.task_id = t.id
                left join operationsflow.shipment s on s.task_id = t.id
            )
            select o.id,o.status,o.created_at,totals.*,
                (select min(a.created_at) from operationsflow.audit_event a
                    join tasks t on t.id = a.target_id where a.action = 'PICK_CONFIRMED') as picking_at,
                (select min(a.created_at) from operationsflow.audit_event a
                    where a.target_id = o.id and a.action = 'ORDER_CANCELLED') as cancelled_at
            from operationsflow.sales_order o cross join totals where o.id = ?
            """, (r,n) -> new Facts(r.getObject("id",UUID.class),OrderStatus.valueOf(r.getString("status")),
                instant(r,"created_at"),instant(r,"picking_at"),r.getLong("tasks"),r.getLong("packages"),
                instant(r,"packed_at"),r.getLong("shipments"),instant(r,"prepared_at"),r.getLong("dispatched"),
                instant(r,"dispatched_at"),instant(r,"cancelled_at")), id,id).stream().findFirst()
            .orElseThrow(() -> new BusinessException("NOT_FOUND","Order not found."));
    }
    private static Instant instant(java.sql.ResultSet row,String column) throws java.sql.SQLException {
        var timestamp = row.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }
}

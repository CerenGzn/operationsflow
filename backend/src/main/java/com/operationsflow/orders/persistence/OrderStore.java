package com.operationsflow.orders.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.operationsflow.orders.api.OrderContracts.*;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.customers.api.CustomerContracts;
import com.operationsflow.inventory.application.OrderInventoryAccess;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.BusinessException;

@Repository
public class OrderStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public OrderStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) { this.jdbc = jdbc; this.named = named; }
    public record Claim(String hash, String response) {}
    public Claim claim(UUID key, String hash) {
        jdbc.execute("set local lock_timeout = '3s'");
        jdbc.update("insert into operationsflow.order_request(id,request_hash) values (?,?) on conflict do nothing", key, hash);
        return jdbc.queryForObject("select request_hash,response::text from operationsflow.order_request where id = ? for update",
            (rs,n) -> new Claim(rs.getString(1),rs.getString(2)),key);
    }
    public void complete(UUID key, String response) { jdbc.update("update operationsflow.order_request set response = cast(? as jsonb) where id = ?",response,key); }
    public void insert(UUID id, CustomerContracts.View customer, Create request, OrderInventoryAccess.Reserved stock) {
        long total = request.lines().stream().mapToLong(Line::quantity).sum();
        jdbc.update("""
            insert into operationsflow.sales_order(id,customer_id,customer_reference,customer_name,shipping_address_line,
                shipping_city,shipping_postal_code,shipping_country_code,status,reservation_id,total_units)
            values (?,?,?,?,?,?,?,?,'RESERVED',?,?)
            """,id,customer.id(),customer.referenceNumber(),customer.name(),customer.addressLine(),customer.city(),customer.postalCode(),customer.countryCode(),stock.reservationId(),total);
        var quantities = new HashMap<UUID,Long>(); request.lines().forEach(l -> quantities.put(l.skuId(),l.quantity()));
        for (var sku : stock.skus()) jdbc.update("insert into operationsflow.order_line(order_id,sku_id,sku_code,product_name,quantity) values (?,?,?,?,?)",
            id,sku.skuId(),sku.skuCode(),sku.productName(),quantities.get(sku.skuId()));
        for (var allocation : stock.allocations()) jdbc.update("insert into operationsflow.order_allocation(order_id,sku_id,warehouse_id,warehouse_code,quantity) values (?,?,?,?,?)",
            id,allocation.skuId(),allocation.warehouseId(),allocation.warehouseCode(),allocation.quantity());
    }
    public void lock(UUID id) {
        jdbc.execute("set local lock_timeout = '3s'");
        if (jdbc.queryForList("select id from operationsflow.sales_order where id = ? for update",UUID.class,id).isEmpty())
            throw new BusinessException("NOT_FOUND","Order not found.");
    }
    public void transition(UUID id, OrderStatus status) {
        jdbc.update("update operationsflow.sales_order set status=?,version=version+1,updated_at=clock_timestamp() where id=?",status.name(),id);
    }
    public void cancel(UUID id, String reason) {
        jdbc.update("update operationsflow.sales_order set status = 'CANCELLED', cancellation_reason = ?, version = version + 1, updated_at = clock_timestamp() where id = ?",reason,id);
    }
    public View get(UUID id) {
        var lines = jdbc.query("select * from operationsflow.order_line where order_id = ? order by sku_code,sku_id",
            (rs,n) -> new LineView(rs.getObject("sku_id",UUID.class),rs.getString("sku_code"),rs.getString("product_name"),rs.getLong("quantity")),id);
        var allocations = jdbc.query("select * from operationsflow.order_allocation where order_id = ? order by warehouse_code,sku_id",
            (rs,n) -> new AllocationView(rs.getObject("sku_id",UUID.class),rs.getObject("warehouse_id",UUID.class),rs.getString("warehouse_code"),rs.getLong("quantity")),id);
        var rows = jdbc.query("select * from operationsflow.sales_order where id = ?", (rs,n) -> new View(
            rs.getObject("id",UUID.class),rs.getString("order_number"),OrderStatus.valueOf(rs.getString("status")),rs.getLong("version"),
            new CustomerSnapshot(rs.getObject("customer_id",UUID.class),rs.getString("customer_reference"),rs.getString("customer_name"),rs.getString("shipping_address_line"),rs.getString("shipping_city"),rs.getString("shipping_postal_code"),rs.getString("shipping_country_code")),
            lines,allocations,rs.getObject("reservation_id",UUID.class),rs.getLong("total_units"),"UNPRICED",null,null,rs.getString("cancellation_reason"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),id);
        if (rows.isEmpty()) throw new BusinessException("NOT_FOUND","Order not found.");
        return rows.getFirst();
    }
    public PageResponse<Summary> list(String query, UUID customerId, OrderStatus status, int page, int size, String sort, String direction) {
        ListQuery.page(page,size,sort,direction,Set.of("createdAt","orderNumber","status","customerName"));
        String term = ListQuery.search(query).toLowerCase(Locale.ROOT);
        var params = new HashMap<String,Object>(); params.put("term",term); params.put("customer",customerId); params.put("status",status == null ? null : status.name());
        params.put("limit",size); params.put("offset",(long)page*size);
        String where = " where (strpos(lower(order_number),:term) > 0 or strpos(lower(customer_name),:term) > 0 or strpos(lower(customer_reference),:term) > 0)" +
            (customerId == null ? "" : " and customer_id = :customer") + (status == null ? "" : " and status = :status");
        String column = Map.of("createdAt","created_at","orderNumber","order_number","status","status","customerName","customer_name").get(sort);
        long total = named.queryForObject("select count(*) from operationsflow.sales_order"+where,params,Long.class);
        var rows = named.query("select * from operationsflow.sales_order"+where+" order by "+column+" "+direction+",id limit :limit offset :offset",params,
            (rs,n) -> new Summary(rs.getObject("id",UUID.class),rs.getString("order_number"),rs.getString("customer_name"),OrderStatus.valueOf(rs.getString("status")),rs.getLong("total_units"),"UNPRICED",rs.getTimestamp("created_at").toInstant()));
        return new PageResponse<>(rows,page,size,total,(int)Math.ceil((double)total/size));
    }
    public boolean hasOpenOrders(UUID customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from operationsflow.sales_order where customer_id = ? and status not in ('CANCELLED','SHIPPED'))",Boolean.class,customerId));
    }
}

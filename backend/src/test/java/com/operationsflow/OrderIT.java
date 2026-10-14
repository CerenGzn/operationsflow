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
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.customers.api.CustomerContracts;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.inventory.api.InventoryContracts;
import com.operationsflow.inventory.application.*;
import com.operationsflow.orders.api.OrderContracts.*;
import com.operationsflow.orders.application.OrderService;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderIT {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired InventoryService inventory;
    @Autowired InventoryQueries inventoryQueries;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    record Fixture(UUID customer, UUID product, UUID sku, UUID warehouse) {}
    private UUID warehouse(String prefix) {
        var id = UUID.randomUUID();
        jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic warehouse','Example street 1','Berlin','10115','DE','ACTIVE',now(),now())",id,prefix+id.toString().substring(0,20).toUpperCase(Locale.ROOT));
        return id;
    }
    private Fixture fixture(long stock) {
        var customer = customers.create(new CustomerContracts.Create("C-"+UUID.randomUUID().toString().substring(0,20).toUpperCase(Locale.ROOT),"Synthetic business","Example street 1","Berlin","10115","DE"));
        var product = products.create(new ProductContracts.Create("Synthetic item","Synthetic description","SKU-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        var f = new Fixture(customer.id(),product.id(),product.skus().getFirst().id(),warehouse("A-"));
        if (stock > 0) receive(f.warehouse(),f.sku(),stock); return f;
    }
    private void receive(UUID warehouse, UUID sku, long quantity) { inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,sku,quantity,"Synthetic receipt")); }
    private Create request(Fixture f,long quantity) { return new Create(f.customer(),List.of(new Line(f.sku(),quantity))); }
    private View create(Fixture f,long quantity) { return orders.create(UUID.randomUUID(),request(f,quantity)); }
    private long reserved(Fixture f) { return jdbc.queryForObject("select coalesce(sum(reserved),0)::bigint from operationsflow.inventory_balance where sku_id = ?",Long.class,f.sku()); }
    private long releases(UUID reservation) { return jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where reservation_id = ? and type = 'RELEASE'",Long.class,reservation); }
    private void reconcile(Fixture f) {
        long movements = jdbc.queryForObject("select coalesce(sum(reserved_delta),0)::bigint from operationsflow.inventory_movement where sku_id = ?",Long.class,f.sku());
        assertThat(reserved(f)).isEqualTo(movements);
        long active = jdbc.queryForObject("select coalesce(sum(l.quantity),0)::bigint from operationsflow.stock_reservation_line l join operationsflow.stock_reservation r on r.id = l.reservation_id where r.state = 'ACTIVE' and l.sku_id = ?",Long.class,f.sku());
        assertThat(reserved(f)).isEqualTo(active);
    }
    private <T> List<T> concurrent(int count,IntFunction<T> action) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i=0;i<count;i++) { final int index=i; futures.add(pool.submit(() -> { ready.countDown(); start.await(); return action.apply(index); })); }
            boolean allReady = ready.await(10,TimeUnit.SECONDS); start.countDown(); assertThat(allReady).isTrue();
            var result = new ArrayList<T>(); for (var future:futures) result.add(future.get(30,TimeUnit.SECONDS)); return result;
        }
    }
    @Test void creationSnapshotsCustomerSkuAndServerCalculatedUnitsWithoutInventedPrices() {
        var f = fixture(10); var order = create(f,4);
        assertThat(order.status()).isEqualTo(OrderStatus.RESERVED); assertThat(order.orderNumber()).startsWith("OF-");
        assertThat(order.totalUnits()).isEqualTo(4); assertThat(order.pricingStatus()).isEqualTo("UNPRICED"); assertThat(order.totalAmount()).isNull(); assertThat(order.currency()).isNull();
        assertThat(order.allocations()).hasSize(1); assertThat(reserved(f)).isEqualTo(4);
        var c = customers.get(f.customer()); customers.update(c.id(),new CustomerContracts.Update("Renamed business","Different street 2","Hamburg","20095","DE",c.version()));
        var p = products.get(f.product()); products.update(p.id(),new ProductContracts.Update("Renamed product","Changed",p.version()));
        assertThat(orders.get(order.id())).isEqualTo(order);
        assertThat(inventoryQueries.reservation(order.reservationId()).orderId()).isEqualTo(order.id()); reconcile(f);
    }
    @Test void prefersWholeOrderInOneWarehouseBeforeSplittingByCode() {
        var f = fixture(2); var second = warehouse("B-"); receive(second,f.sku(),8);
        var single = create(f,5); assertThat(single.allocations()).hasSize(1); assertThat(single.allocations().getFirst().warehouseId()).isEqualTo(second);
        orders.cancel(single.id(),new Cancel("Synthetic cancellation"));
        var split = create(f,9); assertThat(split.allocations()).hasSize(2);
        assertThat(split.allocations().get(0).warehouseId()).isEqualTo(f.warehouse()); assertThat(split.allocations().get(0).quantity()).isEqualTo(2);
        assertThat(split.allocations().get(1).quantity()).isEqualTo(7); reconcile(f);
    }
    @Test void multiSkuOrderAllocatesEachLineAndTotalWithoutClientTotals() {
        var f = fixture(5); var other = fixture(4);
        var result = orders.create(UUID.randomUUID(),new Create(f.customer(),List.of(new Line(other.sku(),3),new Line(f.sku(),2))));
        assertThat(result.lines()).hasSize(2); assertThat(result.totalUnits()).isEqualTo(5); assertThat(result.allocations()).hasSize(2); reconcile(f); reconcile(other);
    }
    @Test void insufficientStockRollsBackOrderReservationKeyAndHistoryAndAllowsRetry() {
        var f = fixture(5); var other = fixture(0); var key = UUID.randomUUID();
        var request = new Create(f.customer(),List.of(new Line(f.sku(),3),new Line(other.sku(),1)));
        long count = jdbc.queryForObject("select count(*) from operationsflow.stock_reservation",Long.class);
        assertThatThrownBy(() -> orders.create(key,request)).isInstanceOf(BusinessException.class);
        assertThat(reserved(f)).isZero(); assertThat(jdbc.queryForObject("select count(*) from operationsflow.order_request where id = ?",Long.class,key)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.stock_reservation",Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.sales_order where customer_id = ?",Long.class,f.customer())).isZero();
        receive(other.warehouse(),other.sku(),1); orders.create(key,request); reconcile(f); reconcile(other);
    }
    @Test void outerFailureAfterCreationRollsBackAllEffects() {
        var f = fixture(5); var key = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> { orders.create(key,request(f,3)); throw new IllegalStateException("Later business failure"); })).isInstanceOf(IllegalStateException.class);
        assertThat(reserved(f)).isZero(); assertThat(jdbc.queryForObject("select count(*) from operationsflow.sales_order where customer_id = ?",Long.class,f.customer())).isZero();
        var result = orders.create(key,request(f,3)); assertThat(result.status()).isEqualTo(OrderStatus.RESERVED); reconcile(f);
    }
    @Test void concurrentOrdersCannotOversellAndDoNotDependOnCustomerSerialization() throws Exception {
        var f = fixture(5); var cs = new ArrayList<UUID>();
        for(int i=0;i<10;i++) cs.add(fixture(0).customer());
        var outcomes = concurrent(10,i -> { try { orders.create(UUID.randomUUID(),new Create(cs.get(i),List.of(new Line(f.sku(),1)))); return "OK"; }
            catch(BusinessException ex) { assertThat(ex.code()).isEqualTo("INSUFFICIENT_STOCK"); return "REJECTED"; } });
        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(5); assertThat(reserved(f)).isEqualTo(5); reconcile(f);
    }
    @Test void concurrentIdempotencyAndReorderedLinesReplayOriginalEvenAfterCancellation() throws Exception {
        var f = fixture(10); var other = fixture(10); var key = UUID.randomUUID();
        var a = new Line(f.sku(),2); var b = new Line(other.sku(),3);
        var replies = concurrent(6,i -> orders.create(key,new Create(f.customer(),i%2==0?List.of(a,b):List.of(b,a))));
        assertThat(replies.stream().map(View::id).distinct()).hasSize(1); assertThat(reserved(f)).isEqualTo(2);
        var first = replies.getFirst(); orders.cancel(first.id(),new Cancel("First reason"));
        assertThat(orders.create(key,new Create(f.customer(),List.of(b,a)))).isEqualTo(first);
        assertThat(orders.get(first.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> orders.create(key,request(f,3))).isInstanceOf(BusinessException.class).hasMessageContaining("idempotency"); reconcile(f); reconcile(other);
    }
    @Test void concurrentCancellationReleasesExactlyOnceAndKeepsFirstReason() throws Exception {
        var f = fixture(10); var result = create(f,6);
        var replies = concurrent(10,i -> orders.cancel(result.id(),new Cancel("Reason "+i)));
        assertThat(replies).allMatch(v -> v.status()==OrderStatus.CANCELLED && v.version()==1);
        assertThat(replies.stream().map(View::cancellationReason).distinct()).hasSize(1);
        assertThat(releases(result.reservationId())).isEqualTo(1); assertThat(reserved(f)).isZero();
        assertThat(inventoryQueries.balances(f.warehouse(),f.sku(),0,20).content().getFirst().onHand()).isEqualTo(10); reconcile(f);
    }
    @Test void cancellationRacingNewOrderPreservesStockAndLedger() throws Exception {
        var f=fixture(5); var original=create(f,5);
        var result=concurrent(2,i -> { if(i==0) { orders.cancel(original.id(),new Cancel("Cancel original")); return "CANCELLED"; }
            try { create(f,5); return "RESERVED"; } catch(BusinessException ex) { assertThat(ex.code()).isEqualTo("INSUFFICIENT_STOCK"); return "REJECTED"; } });
        assertThat(reserved(f)).isEqualTo(result.contains("RESERVED")?5:0); assertThat(releases(original.reservationId())).isEqualTo(1); reconcile(f);
    }
    @Test void orderOwnedReservationsCannotBeReleasedOrConsumedThroughInventory() throws Exception {
        var f=fixture(5); var order=create(f,2);
        for(var action:List.of("release","consume")) mvc.perform(post("/api/v1/inventory/reservations/"+order.reservationId()+"/"+action).contentType("application/json").content("{\"reason\":\"Bypass attempt\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_MANAGED"));
        assertThat(reserved(f)).isEqualTo(2); orders.cancel(order.id(),new Cancel("Via order"));
        assertThatThrownBy(() -> inventory.release(order.reservationId(),new InventoryContracts.Terminal("Retry bypass"))).isInstanceOf(BusinessException.class); reconcile(f);
    }
    @Test void inactiveReferencesAndUnknownSkusRejectOrders() {
        var f=fixture(5); var c=customers.get(f.customer()); customers.deactivate(c.id(),new CustomerContracts.Deactivate(c.version()));
        assertThatThrownBy(() -> create(f,1)).isInstanceOf(BusinessException.class);
        var p=fixture(0); products.deactivate(p.product(),new ProductContracts.Deactivate(0L));
        assertThatThrownBy(() -> create(p,1)).isInstanceOf(BusinessException.class).hasMessageContaining("inactive");
        var w=fixture(5); jdbc.update("update operationsflow.warehouse set status='INACTIVE' where id=?",w.warehouse());
        assertThatThrownBy(() -> create(w,1)).isInstanceOf(BusinessException.class).hasMessageContaining("Insufficient");
        assertThatThrownBy(() -> orders.create(UUID.randomUUID(),new Create(w.customer(),List.of(new Line(UUID.randomUUID(),1))))).isInstanceOf(BusinessException.class).hasMessageContaining("exist");
        assertThat(reserved(f)).isZero(); assertThat(reserved(w)).isZero();
    }
    @Test void openOrdersBlockCustomerDeactivationAndCancellationAllowsIt() {
        var f=fixture(5); var order=create(f,2);
        assertThatThrownBy(() -> customers.deactivate(f.customer(),new CustomerContracts.Deactivate(0L))).isInstanceOf(BusinessException.class).hasMessageContaining("open orders");
        orders.cancel(order.id(),new Cancel("Customer closing")); customers.deactivate(f.customer(),new CustomerContracts.Deactivate(0L));
        assertThat(orders.get(order.id()).customer().name()).isEqualTo("Synthetic business");
    }
    @Test void customerDeactivationRacingCreationHasOnlyOneWinner() throws Exception {
        var f=fixture(5);
        var results=concurrent(2,i -> { try { if(i==0) customers.deactivate(f.customer(),new CustomerContracts.Deactivate(0L)); else create(f,1); return "OK"; }
            catch(BusinessException ex) { assertThat(ex.code()).isIn("OPEN_ORDERS","INACTIVE_RECORD"); return "REJECTED"; } });
        assertThat(results).containsExactlyInAnyOrder("OK","REJECTED"); reconcile(f);
    }
    @Test void cancellationFailureRollsBackOrderAndAllReleases() {
        var f=fixture(5); var order=create(f,2);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> { orders.cancel(order.id(),new Cancel("Rollback cancellation")); throw new IllegalStateException("Failure"); })).isInstanceOf(IllegalStateException.class);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.RESERVED); assertThat(reserved(f)).isEqualTo(2); assertThat(releases(order.reservationId())).isZero(); reconcile(f);
    }
    @Test void databaseProtectsSnapshotsOwnershipAndCancellationReason() {
        var f=fixture(5); var o=create(f,2);
        for(String sql:List.of("update operationsflow.sales_order set customer_name='Changed' where id=?", "update operationsflow.sales_order set status='SHIPPED' where id=?", "update operationsflow.sales_order set status='CANCELLED' where id=?", "delete from operationsflow.sales_order where id=?"))
            assertThatThrownBy(() -> jdbc.update(sql,o.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update operationsflow.order_line set quantity=4 where order_id=?",o.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update operationsflow.stock_reservation set order_id=null where id=?",o.reservationId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update operationsflow.customer set reference_number='CHANGED' where id=?",f.customer())).isInstanceOf(DataIntegrityViolationException.class);
        reconcile(f);
    }
    @Test void orderHttpValidationSearchFiltersAndErrorsAreStructured() throws Exception {
        var f=fixture(5); var payload=json.writeValueAsString(request(f,2)); var key=UUID.randomUUID();
        mvc.perform(post("/api/v1/orders").contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(request(f,0)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(new Create(f.customer(),List.of(new Line(f.sku(),1),new Line(f.sku(),1)))))).andExpect(status().isBadRequest());
        var response=mvc.perform(post("/api/v1/orders").header("Idempotency-Key",key).contentType("application/json").content(payload)).andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        var id=json.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/v1/orders").param("customerId",f.customer().toString()).param("status","RESERVED").param("query","Synthetic")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/orders").param("sort","evil")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/"+id+"/cancel").contentType("application/json").content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders/"+id+"/cancel").contentType("application/json").content("{\"reason\":\"Synthetic cancellation\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }
    @Test void customerHttpValidationUniquenessVersionSearchAndDeactivation() throws Exception {
        var f=fixture(0); var c=customers.get(f.customer());
        var duplicate=new CustomerContracts.Create(c.referenceNumber(),"Other synthetic business","Example street 1","Berlin","10115","DE");
        mvc.perform(post("/api/v1/customers").contentType("application/json").content(json.writeValueAsString(duplicate))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/customers").contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/customers").param("query",c.referenceNumber()).param("status","ACTIVE").param("sort","referenceNumber")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        var update=new CustomerContracts.Update("Changed synthetic business","Example street 2","Berlin","10115","DE",c.version());
        mvc.perform(put("/api/v1/customers/"+c.id()).contentType("application/json").content(json.writeValueAsString(update))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/customers/"+c.id()).contentType("application/json").content(json.writeValueAsString(update))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(post("/api/v1/customers/"+c.id()+"/deactivate").contentType("application/json").content("{\"version\":1}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/customers/"+c.id()).contentType("application/json").content(json.writeValueAsString(new CustomerContracts.Update("Changed","Example 2","Berlin","10115","DE",2L))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INACTIVE_RECORD"));
    }
}

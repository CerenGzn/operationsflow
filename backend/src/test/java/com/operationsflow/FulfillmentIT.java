package com.operationsflow;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.operationsflow.fulfillment.application.*;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.orders.application.OrderService;
import com.operationsflow.orders.api.OrderContracts;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.customers.api.CustomerContracts;
import com.operationsflow.inventory.application.InventoryService;
import com.operationsflow.inventory.api.InventoryContracts;
import com.operationsflow.shared.domain.BusinessException;
import com.operationsflow.security.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FulfillmentIT extends AuthenticatedTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired FulfillmentService fulfillment;@Autowired FulfillmentQueries queries;@Autowired OrderService orders;
    @Autowired ProductService products;@Autowired CustomerService customers;@Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired PlatformTransactionManager transactions;
    record Fixture(UUID order,UUID sku,long totalStock,long demand) {}
    private Fixture fixture(boolean split){
        String key=UUID.randomUUID().toString().substring(0,20).toUpperCase(Locale.ROOT);
        var customer=customers.create(new CustomerContracts.Create("C-"+key,"Synthetic business","Example 1","Berlin","10115","DE"));
        var product=products.create(new ProductContracts.Create("Synthetic fulfillment product","","SKU-"+key));UUID sku=product.skus().getFirst().id();
        for(int i=0;i<(split?2:1);i++){
            UUID warehouse=UUID.randomUUID();jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",warehouse,(i==0?"A-":"B-")+key);
            inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,sku,split?4:8,"Synthetic receipt"));
        }
        long demand=split?7:5;var order=orders.create(UUID.randomUUID(),new OrderContracts.Create(customer.id(),List.of(new OrderContracts.Line(sku,demand))));return new Fixture(order.id(),sku,8,demand);
    }
    private TaskView task(Fixture f){return queries.order(f.order()).tasks().getFirst();}
    private Pack verification(TaskView task){return new Pack(task.version(),task.lines().stream().map(l->new VerifiedLine(l.skuId(),l.quantity())).toList());}
    private TaskView picked(TaskView task){for(var line:task.lines())task=fulfillment.pick(task.id(),line.skuId(),new Pick(line.quantity(),task.version()));return task;}
    private TaskView prepared(TaskView task){task=picked(task);task=fulfillment.pack(task.id(),verification(task));return fulfillment.shipment(task.id(),new PrepareShipment(task.version(),"Synthetic carrier","TRACK-"+task.id()));}
    private void ready(Fixture f){for(var task:queries.order(f.order()).tasks())prepared(task);}
    private long count(String table,UUID order){return jdbc.queryForObject("select count(*) from operationsflow."+table+" where order_id=?",Long.class,order);}
    private long events(){return jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class);}
    private void reconcile(Fixture f,long expectedOnHand,long expectedReserved){
        var balance=jdbc.queryForMap("select sum(on_hand)::bigint as on_hand,sum(reserved)::bigint as reserved from operationsflow.inventory_balance where sku_id=?",f.sku());
        assertThat(balance.get("on_hand")).isEqualTo(expectedOnHand);assertThat(balance.get("reserved")).isEqualTo(expectedReserved);
        var ledger=jdbc.queryForMap("select sum(on_hand_delta)::bigint as on_hand,sum(reserved_delta)::bigint as reserved from operationsflow.inventory_movement where sku_id=?",f.sku());assertThat(ledger).isEqualTo(balance);
        long active=jdbc.queryForObject("select coalesce(sum(l.quantity),0)::bigint from operationsflow.stock_reservation_line l join operationsflow.stock_reservation r on r.id=l.reservation_id where l.sku_id=? and r.state='ACTIVE'",Long.class,f.sku());assertThat(active).isEqualTo(expectedReserved);
    }
    private <T> List<T> concurrent(int n,IntFunction<T> action)throws Exception{
        var auth=SecurityContextHolder.getContext().getAuthentication();var start=new CountDownLatch(1);var ready=new CountDownLatch(n);
        try(var pool=Executors.newFixedThreadPool(n)){var results=new ArrayList<Future<T>>();for(int i=0;i<n;i++){int index=i;results.add(pool.submit(()->{SecurityContextHolder.getContext().setAuthentication(auth);try{ready.countDown();start.await();return action.apply(index);}finally{SecurityContextHolder.clearContext();}}));}boolean all=ready.await(10,TimeUnit.SECONDS);start.countDown();assertThat(all).isTrue();var values=new ArrayList<T>();for(var result:results)values.add(result.get(30,TimeUnit.SECONDS));return values;}
    }
    @Test void pickingCorrectionsPackingAndShipmentPreparationNeverDeductStock(){
        var f=fixture(false);var task=task(f);assertThat(task.state()).isEqualTo(TaskState.PENDING);
        task=fulfillment.pick(task.id(),f.sku(),new Pick(2L,task.version()));assertThat(task.state()).isEqualTo(TaskState.PICKING);assertThat(orders.get(f.order()).status()).isEqualTo(OrderStatus.PROCESSING);
        task=fulfillment.pick(task.id(),f.sku(),new Pick(0L,task.version()));assertThat(task.state()).isEqualTo(TaskState.PENDING);
        task=prepared(task);assertThat(task.state()).isEqualTo(TaskState.READY_TO_SHIP);assertThat(task.packageRecord()).isNotNull();assertThat(task.shipment().state()).isEqualTo("READY");assertThat(orders.get(f.order()).status()).isEqualTo(OrderStatus.READY_TO_SHIP);reconcile(f,8,5);
    }
    @Test void wholeOrderDispatchConsumesExactlyAllocatedQuantitiesAndKeepsHistory(){
        var f=fixture(false);ready(f);var result=fulfillment.dispatch(f.order());assertThat(result.status()).isEqualTo(OrderStatus.SHIPPED);assertThat(result.tasks()).allMatch(t->t.state()==TaskState.DISPATCHED&&t.shipment().dispatchedAt()!=null);
        reconcile(f,3,0);assertThat(queries.shipments(null,"DISPATCHED",0,100).content()).anyMatch(s->s.orderId().equals(f.order()));
        assertThatThrownBy(()->orders.cancel(f.order(),new OrderContracts.Cancel("Cannot restore shipped stock"))).isInstanceOf(BusinessException.class);reconcile(f,3,0);
    }
    @Test void warehousesPrepareIndependentlyButDispatchWaitsForEveryAllocation(){
        var f=fixture(true);var tasks=queries.order(f.order()).tasks();assertThat(tasks).hasSize(2);prepared(tasks.getFirst());
        assertThat(queries.order(f.order()).tasks()).extracting(TaskView::state).containsExactly(TaskState.READY_TO_SHIP,TaskState.PENDING);
        assertThat(orders.get(f.order()).status()).isEqualTo(OrderStatus.PROCESSING);assertThatThrownBy(()->fulfillment.dispatch(f.order())).isInstanceOf(BusinessException.class);reconcile(f,8,7);
        prepared(tasks.getLast());fulfillment.dispatch(f.order());reconcile(f,1,0);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where sku_id=? and type='FULFILLMENT'",Long.class,f.sku())).isEqualTo(2);
    }
    @Test void stalePickingUpdatesConflictAndIdenticalRetriesDoNotDuplicateAudit(){
        var f=fixture(false);var initial=task(f);var request=new Pick(2L,initial.version());var picked=fulfillment.pick(initial.id(),f.sku(),request);long before=events();
        assertThat(fulfillment.pick(initial.id(),f.sku(),request).version()).isEqualTo(picked.version());assertThat(events()).isEqualTo(before);
        assertThatThrownBy(()->fulfillment.pick(initial.id(),f.sku(),new Pick(3L,initial.version()))).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).code()).isEqualTo("STALE_VERSION");
    }
    @Test void packingAndShipmentRetriesAreIdempotentAndChangedPayloadsConflict(){
        var f=fixture(false);var t=picked(task(f));var verification=verification(t);var packed=fulfillment.pack(t.id(),verification);long before=events();
        assertThat(fulfillment.pack(t.id(),verification).packageRecord().id()).isEqualTo(packed.packageRecord().id());assertThat(events()).isEqualTo(before);
        assertThatThrownBy(()->fulfillment.pack(t.id(),new Pack(t.version(),List.of(new VerifiedLine(f.sku(),4))))).isInstanceOf(BusinessException.class);
        var request=new PrepareShipment(packed.version(),"Synthetic carrier","TRACK-"+t.id());var prepared=fulfillment.shipment(t.id(),request);before=events();
        assertThat(fulfillment.shipment(t.id(),request).shipment().id()).isEqualTo(prepared.shipment().id());assertThat(events()).isEqualTo(before);
        assertThatThrownBy(()->fulfillment.shipment(t.id(),new PrepareShipment(prepared.version(),"Other carrier","OTHER"))).isInstanceOf(BusinessException.class);
    }
    @Test void invalidQuantitiesAndOutOfOrderTransitionsAreRejected(){
        var f=fixture(false);var t=task(f);long before=events();
        assertThatThrownBy(()->fulfillment.pick(t.id(),f.sku(),new Pick(6L,t.version()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->fulfillment.pick(t.id(),f.sku(),new Pick(-1L,t.version()))).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThatThrownBy(()->fulfillment.pack(t.id(),verification(t))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->fulfillment.shipment(t.id(),new PrepareShipment(t.version(),"Carrier","TRACK"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->fulfillment.dispatch(f.order())).isInstanceOf(BusinessException.class);
        assertThat(events()).isEqualTo(before);reconcile(f,8,5);
    }
    @Test void packingRequiresEverySkuAndPreventsLaterPickingChanges(){
        var f=fixture(false);var t=picked(task(f));
        assertThatThrownBy(()->fulfillment.pack(t.id(),new Pack(t.version(),List.of(new VerifiedLine(UUID.randomUUID(),5))))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->fulfillment.pack(t.id(),new Pack(t.version(),List.of(new VerifiedLine(f.sku(),5),new VerifiedLine(f.sku(),5))))).isInstanceOf(BusinessException.class);
        fulfillment.pack(t.id(),verification(t));assertThatThrownBy(()->fulfillment.pick(t.id(),f.sku(),new Pick(4L,t.version()+1))).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentDifferentPickingUpdatesCannotOverwriteEachOther()throws Exception{
        var f=fixture(false);var t=task(f);var results=concurrent(2,i->{try{fulfillment.pick(t.id(),f.sku(),new Pick((long)i+1,t.version()));return "OK";}catch(BusinessException e){return e.code();}});
        assertThat(results).containsExactlyInAnyOrder("OK","STALE_VERSION");assertThat(task(f).version()).isEqualTo(1);reconcile(f,8,5);
    }
    @Test void concurrentDuplicatePackingCreatesExactlyOnePackage()throws Exception{
        var f=fixture(false);var t=picked(task(f));var results=concurrent(4,i->fulfillment.pack(t.id(),verification(t)).packageRecord().id());assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.fulfillment_package where task_id=?",Long.class,t.id())).isEqualTo(1);
    }
    @Test void concurrentDuplicateDispatchConsumesOnce()throws Exception{
        var f=fixture(true);ready(f);var results=concurrent(5,i->fulfillment.dispatch(f.order()).status());assertThat(results).containsOnly(OrderStatus.SHIPPED);reconcile(f,1,0);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where sku_id=? and type='FULFILLMENT'",Long.class,f.sku())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event where target_id=? and action='ORDER_DISPATCHED'",Long.class,f.order())).isEqualTo(1);
    }
    @Test void dispatchAndCancellationRaceHasOneWinnerWithoutMixedStates()throws Exception{
        var f=fixture(true);ready(f);var results=concurrent(2,i->{try{return i==0?fulfillment.dispatch(f.order()).status().name():orders.cancel(f.order(),new OrderContracts.Cancel("Synthetic cancellation")).status().name();}catch(BusinessException e){return e.code();}});
        assertThat(results).contains("INVALID_TRANSITION");var status=orders.get(f.order()).status();assertThat(status).isIn(OrderStatus.SHIPPED,OrderStatus.CANCELLED);
        assertThat(queries.order(f.order()).tasks()).allMatch(t->t.state()==(status==OrderStatus.SHIPPED?TaskState.DISPATCHED:TaskState.CANCELLED));reconcile(f,status==OrderStatus.SHIPPED?1:8,0);
    }
    @Test void cancellationClosesPreparedAndUnpreparedWarehousesExactlyOnce(){
        var f=fixture(true);prepared(task(f));orders.cancel(f.order(),new OrderContracts.Cancel("Synthetic cancellation"));long before=events();orders.cancel(f.order(),new OrderContracts.Cancel("Retry"));assertThat(events()).isEqualTo(before);
        assertThat(queries.order(f.order()).tasks()).allMatch(t->t.state()==TaskState.CANCELLED);assertThat(task(f).shipment().state()).isEqualTo("CANCELLED");assertThat(task(f).packageRecord()).isNotNull();reconcile(f,8,0);
        assertThatThrownBy(()->fulfillment.dispatch(f.order())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->fulfillment.pack(task(f).id(),verification(task(f)))).isInstanceOf(BusinessException.class);
    }
    @Test void outerTransactionRollbackRestoresStockStatesAndAudit(){
        var f=fixture(true);ready(f);long before=events();assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{fulfillment.dispatch(f.order());throw new IllegalStateException("Synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isEqualTo(before);assertThat(orders.get(f.order()).status()).isEqualTo(OrderStatus.READY_TO_SHIP);assertThat(queries.order(f.order()).tasks()).allMatch(t->t.state()==TaskState.READY_TO_SHIP&&t.shipment().dispatchedAt()==null);reconcile(f,8,7);
    }
    @Test void databaseConstraintsProtectQuantitiesPackagesAndTracking(){
        var f=fixture(false);var t=task(f);
        assertThatThrownBy(()->jdbc.update("update operationsflow.fulfillment_line set picked_quantity=quantity+1 where task_id=?",t.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update operationsflow.fulfillment_line set quantity=quantity+1 where task_id=?",t.id())).isInstanceOf(DataIntegrityViolationException.class);
        var ready=prepared(t);assertThatThrownBy(()->jdbc.update("delete from operationsflow.fulfillment_package where task_id=?",t.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update operationsflow.shipment set tracking_reference='OTHER' where task_id=?",t.id())).isInstanceOf(DataIntegrityViolationException.class);
        var other=fixture(false);var packed=fulfillment.pack(picked(task(other)).id(),verification(task(other)));assertThatThrownBy(()->fulfillment.shipment(packed.id(),new PrepareShipment(packed.version(),ready.shipment().carrier().toUpperCase(Locale.ROOT),ready.shipment().trackingReference()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(task(other).state()).isEqualTo(TaskState.PACKED);
    }
    @Test void fulfillmentAuditAndMovementsIdentifyTheActor(){
        var f=fixture(false);ready(f);fulfillment.dispatch(f.order());
        assertThat(jdbc.queryForObject("select detail from operationsflow.audit_event where target_id=? and action='PICK_CONFIRMED'",String.class,task(f).id())).isEqualTo("sku="+f.sku()+";picked=0->5");
        assertThat(jdbc.queryForList("select actor_id from operationsflow.audit_event where target_id=? and action='ORDER_DISPATCHED'",UUID.class,f.order())).containsExactly(TEST_ADMIN);
        assertThat(jdbc.queryForList("select actor from operationsflow.inventory_movement where sku_id=? and type='FULFILLMENT'",String.class,f.sku())).containsExactly("user:"+TEST_ADMIN);
    }
    @Test void failureDuringSecondWarehouseDeductionRollsBackTheFirstMovementAndAudit(){
        var f=fixture(true);ready(f);var second=queries.order(f.order()).tasks().getLast().warehouseId();long before=events();
        jdbc.execute("""
            create function operationsflow.synthetic_dispatch_failure() returns trigger language plpgsql as $$
            begin
                if NEW.type='FULFILLMENT' and NEW.warehouse_id::text=TG_ARGV[0] then
                    if not exists(select 1 from operationsflow.inventory_movement where sku_id=NEW.sku_id and type='FULFILLMENT') then
                        raise exception 'Expected the first warehouse movement before fault injection';
                    end if;
                    raise exception 'Synthetic second-warehouse failure' using errcode='23514';
                end if;
                return NEW;
            end;
            $$
            """);
        jdbc.execute("create trigger synthetic_dispatch_failure before insert on operationsflow.inventory_movement for each row execute function operationsflow.synthetic_dispatch_failure('"+second+"')");
        try {
            assertThatThrownBy(()->fulfillment.dispatch(f.order())).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(events()).isEqualTo(before);reconcile(f,8,7);
            assertThat(jdbc.queryForObject("select count(*) from operationsflow.inventory_movement where sku_id=? and type='FULFILLMENT'",Long.class,f.sku())).isZero();
            assertThat(queries.order(f.order()).tasks()).allMatch(t->t.state()==TaskState.READY_TO_SHIP && t.shipment().dispatchedAt()==null);
        } finally {jdbc.execute("drop trigger synthetic_dispatch_failure on operationsflow.inventory_movement");jdbc.execute("drop function operationsflow.synthetic_dispatch_failure()");}
    }
    @Test void multipleSkusMustAllBePickedAndVerifiedBeforePacking(){
        var base=fixture(false);var warehouse=task(base).warehouseId();var second=products.create(new ProductContracts.Create("Synthetic second item","","SECOND-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT))).skus().getFirst().id();
        inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,second,4,"Synthetic second item receipt"));
        var order=orders.create(UUID.randomUUID(),new OrderContracts.Create(orders.get(base.order()).customer().id(),List.of(new OrderContracts.Line(base.sku(),2),new OrderContracts.Line(second,3))));
        var task=queries.order(order.id()).tasks().getFirst();assertThat(task.lines()).hasSize(2);
        var first=fulfillment.pick(task.id(),base.sku(),new Pick(2L,task.version()));assertThat(first.state()).isEqualTo(TaskState.PICKING);
        assertThatThrownBy(()->fulfillment.pack(first.id(),verification(first))).isInstanceOf(BusinessException.class);
        var complete=fulfillment.pick(task.id(),second,new Pick(3L,first.version()));
        assertThatThrownBy(()->fulfillment.pack(complete.id(),new Pack(complete.version(),List.of(new VerifiedLine(base.sku(),2))))).isInstanceOf(BusinessException.class);
        assertThat(fulfillment.pack(complete.id(),verification(complete)).state()).isEqualTo(TaskState.PACKED);
    }
    private org.springframework.security.core.Authentication identity(Role role){UUID id=UUID.randomUUID();jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) select ?,?,'Synthetic operator',password_hash,?,false from operationsflow.app_user where id=?",id,id+"@example.invalid",role.name(),TEST_ADMIN);return UsernamePasswordAuthenticationToken.authenticated(new SessionActor(id,role,0),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @Test void backendRolesCsrfAndValidationProtectFulfillmentRoutes()throws Exception{
        var f=fixture(false);var task=task(f);var sales=identity(Role.SALES_OPERATOR);var warehouse=identity(Role.WAREHOUSE_OPERATOR);
        for(String path:List.of("/api/v1/fulfillment/tasks","/api/v1/fulfillment/orders/"+f.order(),"/api/v1/fulfillment/shipments")){
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());mvc.perform(get(path).with(authentication(sales))).andExpect(status().isOk());
        }
        for(String path:List.of("/api/v1/fulfillment/tasks/"+task.id()+"/pack","/api/v1/fulfillment/tasks/"+task.id()+"/shipment","/api/v1/fulfillment/orders/"+f.order()+"/dispatch"))mvc.perform(post(path).with(authentication(sales)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String path="/api/v1/fulfillment/tasks/"+task.id()+"/picks/"+f.sku();
        mvc.perform(put(path).with(authentication(sales)).contentType("application/json").content("{\"quantity\":1,\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(warehouse)).with(csrf().useInvalidToken()).contentType("application/json").content("{\"quantity\":1,\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(warehouse)).contentType("application/json").content("{\"version\":0}")).andExpect(status().isBadRequest());
        mvc.perform(put(path).with(authentication(warehouse)).contentType("application/json").content("{\"quantity\":1,\"version\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PICKING"));
        mvc.perform(get("/api/v1/fulfillment/tasks").param("size","101")).andExpect(status().isBadRequest());
    }
    @Autowired com.operationsflow.orders.application.OrderTimelineService timeline;
    private com.operationsflow.orders.api.OrderTimeline.Milestone milestone(Fixture f,String code) {
        return timeline.get(f.order()).milestones().stream().filter(m->m.code().equals(code)).findFirst().orElseThrow();
    }
    @Test void timelineUsesPersistedInstantsAndDistinguishesPreparationFromDispatch() {
        var f=fixture(false);
        assertThat(milestone(f,"CONFIRMED").occurredAt()).isEqualTo(orders.get(f.order()).createdAt());
        assertThat(milestone(f,"PREPARING").occurredAt()).isNull();
        assertThat(milestone(f,"SHIPPED").progress().name()).isEqualTo("PENDING");
        assertThat(timeline.get(f.order()).milestones()).noneMatch(m->m.code().equals("SHIPMENT_PREPARED"));
        var picked=picked(task(f));
        var firstPick=jdbc.queryForObject("select min(created_at) from operationsflow.audit_event where target_id=? and action='PICK_CONFIRMED'",java.sql.Timestamp.class,picked.id()).toInstant();
        assertThat(milestone(f,"PREPARING").occurredAt()).isEqualTo(firstPick);
        var packed=fulfillment.pack(picked.id(),verification(picked));
        assertThat(milestone(f,"PACKED").occurredAt()).isEqualTo(packed.packageRecord().packedAt());
        var prepared=fulfillment.shipment(packed.id(),new PrepareShipment(packed.version(),"Synthetic carrier","T-"+packed.id()));
        assertThat(milestone(f,"SHIPMENT_PREPARED").occurredAt()).isEqualTo(prepared.shipment().createdAt());
        assertThat(milestone(f,"SHIPPED").occurredAt()).isNull();
        var dispatched=fulfillment.dispatch(f.order());
        assertThat(milestone(f,"SHIPPED").occurredAt()).isEqualTo(dispatched.tasks().getFirst().shipment().dispatchedAt());
        assertThat(milestone(f,"SHIPPED").progress().name()).isEqualTo("COMPLETED");
        reconcile(f,3,0);
    }
    @Test void timelineRequiresAllWarehousesAndRetainsConfirmedHistoryAfterCancellation() {
        var f=fixture(true);prepared(task(f));
        assertThat(milestone(f,"PACKED").progress().name()).isEqualTo("IN_PROGRESS");
        assertThat(milestone(f,"PACKED").occurredAt()).isNull();
        assertThat(milestone(f,"SHIPMENT_PREPARED").detail()).startsWith("1 of 2");
        orders.cancel(f.order(),new OrderContracts.Cancel("Synthetic cancellation"));
        assertThat(milestone(f,"SHIPPED").progress().name()).isEqualTo("NOT_REACHED");
        assertThat(milestone(f,"SHIPPED").occurredAt()).isNull();
        assertThat(milestone(f,"PREPARING").occurredAt()).isNotNull();
        var at=jdbc.queryForObject("select created_at from operationsflow.audit_event where target_id=? and action='ORDER_CANCELLED'",java.sql.Timestamp.class,f.order()).toInstant();
        assertThat(milestone(f,"CANCELLED").occurredAt()).isEqualTo(at);
        var before=timeline.get(f.order());orders.cancel(f.order(),new OrderContracts.Cancel("Retry"));
        assertThat(timeline.get(f.order())).isEqualTo(before);reconcile(f,8,0);
    }
    @Test void timelineAggregationUsesLastWarehouseCompletionAndDoesNotExposeRolledBackEvents() {
        var f=fixture(true);ready(f);
        var tasks=queries.order(f.order()).tasks();
        assertThat(milestone(f,"PACKED").occurredAt()).isEqualTo(tasks.stream().map(t->t.packageRecord().packedAt()).max(java.time.Instant::compareTo).orElseThrow());
        var before=timeline.get(f.order());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{fulfillment.dispatch(f.order());throw new IllegalStateException("Synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(timeline.get(f.order())).isEqualTo(before);
    }
    @Test void timelineGetIsReadOnlyAndPreservesRoleRestrictions() throws Exception {
        var f=fixture(false);long before=events();var order=orders.get(f.order());String path="/api/v1/orders/"+f.order()+"/timeline";
        for(var role:List.of(Role.ADMIN,Role.SALES_OPERATOR,Role.WAREHOUSE_OPERATOR)) {
            mvc.perform(get(path).with(authentication(identity(role)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.milestones[0].code").value("CONFIRMED"))
                .andExpect(jsonPath("$.customer").doesNotExist()).andExpect(jsonPath("$.trackingReference").doesNotExist());
        }
        mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(identity(Role.DEMO_VIEWER)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/"+UUID.randomUUID()+"/timeline")).andExpect(status().isNotFound());
        assertThat(events()).isEqualTo(before);assertThat(orders.get(f.order())).isEqualTo(order);reconcile(f,8,5);
    }

}

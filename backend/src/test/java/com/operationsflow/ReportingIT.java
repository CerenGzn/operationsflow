package com.operationsflow;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.operationsflow.reporting.application.ReportingService;
import com.operationsflow.reporting.api.ReportingContracts.*;
import com.operationsflow.reporting.domain.*;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.customers.api.CustomerContracts;
import com.operationsflow.orders.application.OrderService;
import com.operationsflow.orders.api.OrderContracts;
import com.operationsflow.inventory.application.InventoryService;
import com.operationsflow.inventory.api.InventoryContracts;
import com.operationsflow.fulfillment.application.*;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.security.domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Every test rolls back its synthetic records; production security and database constraints stay enabled. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @Transactional
class ReportingIT extends AuthenticatedTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired ReportingService reporting; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
    @Autowired ProductService products; @Autowired CustomerService customers; @Autowired OrderService orders;
    @Autowired InventoryService inventory; @Autowired FulfillmentService fulfillment; @Autowired FulfillmentQueries fulfillmentQueries;
    private DateWindow range(){return DateWindow.of(null,null,"Europe/Berlin");}
    private ReportFilter filter(UUID warehouse,UUID sku,String status,String query){return new ReportFilter(range(),warehouse,sku,status,query,10);}
    record Fixture(UUID warehouse,UUID sku,UUID customer,String skuCode){}
    private Fixture fixture(){
        String key=UUID.randomUUID().toString().substring(0,16).toUpperCase(Locale.ROOT);
        var product=products.create(new ProductContracts.Create("=Synthetic formula test","","SKU-"+key));
        var customer=customers.create(new CustomerContracts.Create("C-"+key,"Synthetic customer","Private synthetic address","Berlin","10115","DE"));
        UUID warehouse=UUID.randomUUID();jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",warehouse,"WH-"+key);
        UUID sku=product.skus().getFirst().id();inventory.receive(UUID.randomUUID(),new InventoryContracts.Receipt(warehouse,sku,20,"Synthetic receipt"));
        return new Fixture(warehouse,sku,customer.id(),"SKU-"+key);
    }
    private UUID order(Fixture f,long quantity){return orders.create(UUID.randomUUID(),new OrderContracts.Create(f.customer(),List.of(new OrderContracts.Line(f.sku(),quantity)))).id();}
    private void prepare(UUID order){
        var task=fulfillmentQueries.order(order).tasks().getFirst();
        for(var line:task.lines())task=fulfillment.pick(task.id(),line.skuId(),new Pick(line.quantity(),task.version()));
        task=fulfillment.pack(task.id(),new Pack(task.version(),task.lines().stream().map(l->new VerifiedLine(l.skuId(),l.quantity())).toList()));
        fulfillment.shipment(task.id(),new PrepareShipment(task.version(),"Synthetic carrier","TRACK-"+order));
    }
    private long sum(List<Point> points){return points.stream().mapToLong(p->Long.parseLong(p.value())).sum();}
    @Test void emptyBusinessDatabaseHasTrueZerosAndZeroFilledDays(){
        var d=reporting.dashboard(range(),10);assertThat(d.kpis().values()).containsOnly("0");
        assertThat(d.orderVolume()).hasSize(30);assertThat(sum(d.orderVolume())).isZero();
        assertThat(d.inventoryByWarehouse()).isEmpty();assertThat(d.topSkus()).isEmpty();assertThat(d.recentActivity()).isEmpty();
        for(var kind:ReportKind.values())assertThat(reporting.report(kind,filter(null,null,"",""),0,20).totalElements()).isZero();
        assertThat(reporting.export(ReportKind.ORDERS,filter(null,null,"",""))).startsWith("\"Order ID\"").hasLineCount(1);
    }
    @Test void lifecycleKpisChartsAndLedgerAgreeWithoutMutatingBusinessData(){
        var f=fixture();order(f,2);var cancelled=order(f,3);orders.cancel(cancelled,new OrderContracts.Cancel("Synthetic cancellation"));
        var ready=order(f,4);prepare(ready);var shipped=order(f,5);prepare(shipped);fulfillment.dispatch(shipped);
        long auditBefore=jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class);
        var d=reporting.dashboard(range(),10);
        assertThat(d.kpis()).containsEntry("total_orders","4").containsEntry("open_orders","2").containsEntry("cancelled_orders","1").containsEntry("shipped_orders","1").containsEntry("awaiting_fulfillment","1").containsEntry("inventory_units","15").containsEntry("reserved_units","6").containsEntry("available_units","9").containsEntry("low_stock","1").containsEntry("active_products","1").containsEntry("active_warehouses","1").containsEntry("shipments_awaiting_dispatch","1");
        assertThat(sum(d.orderVolume())).isEqualTo(4);assertThat(sum(d.orderStatus())).isEqualTo(4);assertThat(sum(d.shipmentThroughput())).isEqualTo(1);assertThat(sum(d.fulfillmentStatus())).isEqualTo(4);
        assertThat(d.topSkus()).containsExactly(new Point(f.skuCode(),"3","11"));
        assertThat(sum(d.movementTrends())).isEqualTo(15);assertThat(d.movementTrends().stream().mapToLong(p->Long.parseLong(p.secondary())).sum()).isEqualTo(6);
        var stock=reporting.report(ReportKind.INVENTORY,filter(f.warehouse(),f.sku(),"",""),0,20).content().getFirst();
        assertThat(stock).containsEntry("on_hand","15").containsEntry("reserved","6").containsEntry("available","9");
        assertThat(d.recentActivity()).anyMatch(a->a.type().equals("SHIPMENT_DISPATCHED"));
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select sum(on_hand_delta)::bigint from operationsflow.inventory_movement",Long.class)).isEqualTo(15);
    }
    @Test void paginationFiltersAndFullCsvUseTheSameServerDataset(){
        var f=fixture();var first=order(f,1);order(f,2);var last=order(f,3);orders.cancel(last,new OrderContracts.Cancel("Synthetic"));
        var all=filter(f.warehouse(),f.sku(),"","");var page0=reporting.report(ReportKind.ORDERS,all,0,1);var page1=reporting.report(ReportKind.ORDERS,all,1,1);
        assertThat(page0.totalElements()).isEqualTo(3);assertThat(page0.totalPages()).isEqualTo(3);assertThat(page0.content()).hasSize(1).doesNotContainAnyElementsOf(page1.content());
        String csv=reporting.export(ReportKind.ORDERS,all);assertThat(csv).hasLineCount(4).contains(first.toString(),last.toString()).doesNotContain("Private synthetic address","Synthetic customer");
        assertThat(reporting.report(ReportKind.ORDERS,filter(f.warehouse(),f.sku(),"CANCELLED",""),0,20).content()).singleElement().satisfies(row->assertThat(row.get("id")).isEqualTo(last.toString()));
        assertThat(reporting.report(ReportKind.ORDERS,filter(null,null,"","' OR 1=1 --"),0,20).totalElements()).isZero();
        assertThat(reporting.report(ReportKind.ORDERS,filter(UUID.randomUUID(),null,"",""),0,20).totalElements()).isZero();
        assertThat(reporting.export(ReportKind.INVENTORY,all)).contains("\"'=Synthetic formula test\"");
    }
    @Test void lowStockUsesInclusiveAvailableThresholdAndExcludesInactivePairs(){
        var f=fixture();order(f,10);
        assertThat(reporting.report(ReportKind.LOW_STOCK,filter(null,null,"",""),0,20).totalElements()).isEqualTo(1);
        assertThat(reporting.report(ReportKind.LOW_STOCK,new ReportFilter(range(),null,null,"","",9),0,20).totalElements()).isZero();
        jdbc.update("update operationsflow.warehouse set status='INACTIVE' where id=?",f.warehouse());
        assertThat(reporting.dashboard(range(),10).kpis()).containsEntry("low_stock","0").containsEntry("inventory_units","20");
    }
    @Test void berlinDstSqlBucketsAndHalfOpenReportBoundariesAgree(){
        var f=fixture();var day=LocalDate.parse("2000-03-26");var window=DateWindow.of(day,day,"Europe/Berlin");
        // Separate synthetic historical receipts: each inserted ledger effect is reflected in its balance.
        var instants=List.of(window.start().minusNanos(1000),window.start(),window.end().minusNanos(1000),window.end());
        for(int i=0;i<instants.size();i++){
            jdbc.update("insert into operationsflow.inventory_movement(id,warehouse_id,sku_id,type,on_hand_delta,reserved_delta,on_hand_after,reserved_after,operation_id,reason,actor,created_at) values (?, ?, ?, 'RECEIPT',1,0,?,0,?,'Synthetic boundary','UNAUTHENTICATED',?)",UUID.randomUUID(),f.warehouse(),f.sku(),21+i,UUID.randomUUID(),Timestamp.from(instants.get(i)));
        }
        jdbc.update("update operationsflow.inventory_balance set on_hand=24 where warehouse_id=? and sku_id=?",f.warehouse(),f.sku());
        var data=reporting.dashboard(window,10);assertThat(data.movementTrends()).containsExactly(new Point("2000-03-26","2","0"));
        assertThat(reporting.report(ReportKind.MOVEMENTS,new ReportFilter(window,f.warehouse(),f.sku(),"","",10),0,20).totalElements()).isEqualTo(2);
        assertThat(data.kpis().get("inventory_units")).isEqualTo("24"); // KPIs are not date-filtered.
    }
    private org.springframework.security.core.Authentication identity(Role role){
        UUID id=UUID.randomUUID();jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) select ?,?,'Synthetic operator',password_hash,?,false from operationsflow.app_user where id=?",id,id+"@example.invalid",role.name(),TEST_ADMIN);
        return UsernamePasswordAuthenticationToken.authenticated(new SessionActor(id,role,0),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    @Test void everyReportAndExportEnforcesBackendRolesAndAnonymousDenial()throws Exception{
        var sales=identity(Role.SALES_OPERATOR);var warehouse=identity(Role.WAREHOUSE_OPERATOR);
        for(var kind:ReportKind.values())for(String suffix:List.of("","/export")){
            String path="/api/v1/reports/"+kind.path+suffix;
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
            mvc.perform(get(path)).andExpect(status().isOk());
            mvc.perform(get(path).with(authentication(sales))).andExpect(status().is(kind==ReportKind.AUDIT||kind==ReportKind.MOVEMENTS?403:200));
            mvc.perform(get(path).with(authentication(warehouse))).andExpect(status().is(kind==ReportKind.AUDIT?403:200));
        }
        mvc.perform(get("/api/v1/analytics/dashboard").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/analytics/dashboard").with(authentication(sales))).andExpect(status().isOk()).andExpect(jsonPath("$.movementTrends").doesNotExist());
        mvc.perform(get("/api/v1/analytics/dashboard").with(authentication(warehouse))).andExpect(status().isOk()).andExpect(jsonPath("$.movementTrends").isArray());
        SecurityContextHolder.getContext().setAuthentication(sales);
        assertThatThrownBy(()->reporting.export(ReportKind.AUDIT,filter(null,null,"",""))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void invalidFiltersAndExcessiveExportsReturnStructuredErrorsWithoutPartialCsv()throws Exception{
        mvc.perform(get("/api/v1/reports/orders").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/orders").param("status","DISPATCHED")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analytics/dashboard").param("zone","Asia/Tokyo")).andExpect(status().isBadRequest());
        jdbc.update("insert into operationsflow.audit_event(id,actor_kind,action,detail) select gen_random_uuid(),'SYSTEM','SYNTHETIC_LIMIT','Excluded detail' from generate_series(1,5001)");
        mvc.perform(get("/api/v1/reports/audit/export").param("status","SYNTHETIC_LIMIT")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"));
        mvc.perform(get("/api/v1/reports/audit/export").param("status","SYNTHETIC_ABSENT")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().contentTypeCompatibleWith("text/csv"));
        assertThat(reporting.report(ReportKind.AUDIT,filter(null,null,"SYNTHETIC_LIMIT",""),0,20).content()).allSatisfy(row->assertThat(row).doesNotContainKeys("detail","email","password_hash"));
    }
    @Test void viewerServiceGuardRejectsSensitiveReportsAndEveryExport(){
        SecurityContextHolder.getContext().setAuthentication(identity(Role.DEMO_VIEWER));
        for(var kind:ReportKind.values()){
            assertThatThrownBy(()->reporting.export(kind,filter(null,null,"",""))).isInstanceOf(AccessDeniedException.class);
            if(kind==ReportKind.AUDIT || kind==ReportKind.SHIPMENTS)
                assertThatThrownBy(()->reporting.report(kind,filter(null,null,"",""),0,20)).isInstanceOf(AccessDeniedException.class);
            else assertThat(reporting.report(kind,filter(null,null,"",""),0,20).content()).isEmpty();
        }
        assertThat(reporting.dashboard(range(),10).kpis().values()).containsOnly("0");
    }

}

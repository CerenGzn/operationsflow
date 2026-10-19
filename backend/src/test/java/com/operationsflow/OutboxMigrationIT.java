package com.operationsflow;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxMigrationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void additiveUpgradePreservesExistingUsersAndDoesNotBackfillEvents() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).target("8").load().migrate(); var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role) values (?,'migration@example.invalid','Synthetic migration user',?,'DEMO_VIEWER')",UUID.randomUUID(),new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("Synthetic-migration-only-13!"));
        seedExistingBusiness(jdbc, ds);
        // Snapshot every pre-existing application table, including stock, history and fulfillment.
        var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema='operationsflow' and table_type='BASE TABLE' order by table_name",String.class);
        var before = new TreeMap<String,List<String>>();
        for (var table : tables) before.put(table,snapshot(jdbc,table));
        var flyway = Flyway.configure().dataSource(ds).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.migrate().migrationsExecuted).isZero();
        for (var table : tables) assertThat(snapshot(jdbc,table)).as(table+" unchanged by V9").isEqualTo(before.get(table));
        assertThat(before.get("sales_order")).hasSize(1);
        assertThat(before.get("inventory_movement")).hasSize(2);
        assertThat(before.get("fulfillment_task")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.event_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.event_inbox",Long.class)).isZero();
    }
    private List<String> snapshot(JdbcTemplate jdbc,String table) {
        return jdbc.queryForList("select to_jsonb(t)::text from operationsflow."+table+" t order by 1",String.class);
    }
    private void seedExistingBusiness(JdbcTemplate jdbc,DataSource ds) {
        UUID product=UUID.randomUUID(),sku=UUID.randomUUID(),warehouse=UUID.randomUUID(),customer=UUID.randomUUID(),reservation=UUID.randomUUID(),order=UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx->{
            jdbc.update("insert into operationsflow.product(id,name,description,status,created_at,updated_at) values (?,'Synthetic legacy item','','ACTIVE',now(),now())",product);
            jdbc.update("insert into operationsflow.sku(id,product_id,code) values (?,?,'LEGACY-SKU')",sku,product);
            jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,'LEGACY-WH','Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",warehouse);
            jdbc.update("insert into operationsflow.customer(id,reference_number,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,'LEGACY-C','Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())",customer);
            jdbc.update("insert into operationsflow.stock_reservation(id,owner_reference,state,order_id) values (?,?,'ACTIVE',?)",reservation,"order:"+order,order);
            jdbc.update("insert into operationsflow.sales_order(id,customer_id,customer_reference,customer_name,shipping_address_line,shipping_city,shipping_postal_code,shipping_country_code,status,reservation_id,total_units) values (?,?,'LEGACY-C','Synthetic','Example 1','Berlin','10115','DE','RESERVED',?,3)",order,customer,reservation);
            jdbc.update("insert into operationsflow.order_line(order_id,sku_id,sku_code,product_name,quantity) values (?,?,'LEGACY-SKU','Synthetic legacy item',3)",order,sku);
            jdbc.update("insert into operationsflow.order_allocation(order_id,sku_id,warehouse_id,warehouse_code,quantity) values (?,?,?,'LEGACY-WH',3)",order,sku,warehouse);
            jdbc.update("insert into operationsflow.inventory_balance(warehouse_id,sku_id,on_hand,reserved) values (?,?,5,3)",warehouse,sku);
            jdbc.update("insert into operationsflow.stock_reservation_line(reservation_id,warehouse_id,sku_id,quantity) values (?,?,?,3)",reservation,warehouse,sku);
            jdbc.update("insert into operationsflow.inventory_movement(id,warehouse_id,sku_id,type,on_hand_delta,reserved_delta,on_hand_after,reserved_after,operation_id,reason,actor) values (?,?,?,'RECEIPT',5,0,5,0,?,'Synthetic legacy receipt','UNAUTHENTICATED')",UUID.randomUUID(),warehouse,sku,UUID.randomUUID());
            jdbc.update("insert into operationsflow.inventory_movement(id,warehouse_id,sku_id,type,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reservation_id,operation_id,reason,actor) values (?,?,?,'RESERVATION',0,3,5,3,?,?,'Synthetic legacy reservation','UNAUTHENTICATED')",UUID.randomUUID(),warehouse,sku,reservation,UUID.randomUUID());
            UUID task=UUID.randomUUID();
            jdbc.update("insert into operationsflow.fulfillment_task(id,order_id,warehouse_id) values (?,?,?)",task,order,warehouse);
            jdbc.update("insert into operationsflow.fulfillment_line(task_id,order_id,warehouse_id,sku_id,quantity) values (?,?,?,?,3)",task,order,warehouse,sku);
            jdbc.update("insert into operationsflow.audit_event(id,actor_kind,action,target_id) values (?,'SYSTEM','SYNTHETIC_MIGRATION_FIXTURE',?)",UUID.randomUUID(),order);
        });
    }
}

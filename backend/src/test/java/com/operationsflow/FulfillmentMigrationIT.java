package com.operationsflow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FulfillmentMigrationIT {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void upgradesExistingReservedOrdersWithoutChangingStockOrInventingAuditActors(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).target("5").load().migrate();var jdbc=new JdbcTemplate(ds);
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
        });
        var flyway=Flyway.configure().dataSource(ds).target("6").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.migrate().migrationsExecuted).isZero();
        var task=jdbc.queryForMap("select * from operationsflow.fulfillment_task where order_id=?",order);assertThat(task.get("state")).isEqualTo("PENDING");assertThat(task.get("warehouse_id")).isEqualTo(warehouse);
        var line=jdbc.queryForMap("select * from operationsflow.fulfillment_line where order_id=?",order);assertThat(line.get("quantity")).isEqualTo(3L);assertThat(line.get("picked_quantity")).isEqualTo(0L);
        var balance=jdbc.queryForMap("select on_hand,reserved from operationsflow.inventory_balance");assertThat(balance.get("on_hand")).isEqualTo(5L);assertThat(balance.get("reserved")).isEqualTo(3L);
        assertThat(jdbc.queryForList("select actor from operationsflow.inventory_movement",String.class)).containsOnly("UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class)).isZero();
    }
}

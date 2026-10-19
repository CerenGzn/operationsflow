package com.operationsflow;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.catalog.api.ProductContracts;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.customers.api.CustomerContracts;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.inventory.api.InventoryContracts;
import com.operationsflow.inventory.application.InventoryService;
import com.operationsflow.orders.api.OrderContracts;
import com.operationsflow.orders.application.OrderService;
import com.operationsflow.fulfillment.application.*;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.messaging.application.*;
import com.operationsflow.messaging.persistence.OutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

abstract class MessagingTestSupport extends AuthenticatedTestSupport {
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired InventoryService inventory;
    @Autowired FulfillmentService fulfillment;
    @Autowired FulfillmentQueries fulfillmentQueries;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxStore outbox;
    @Autowired OrderEvents events;
    @Autowired EventConsumer consumer;
    record Fixture(UUID customer, UUID sku, UUID warehouse) {}
    @BeforeEach void clearMessaging() {
        jdbc.execute("truncate operationsflow.event_outbox, operationsflow.order_event_ledger, operationsflow.event_inbox");
    }
    Fixture fixture() {
        var suffix = UUID.randomUUID().toString().substring(0,20).toUpperCase(Locale.ROOT);
        var customer = customers.create(new CustomerContracts.Create("C-"+suffix,"Synthetic business","Example 1","Berlin","10115","DE"));
        var product = products.create(new ProductContracts.Create("Synthetic messaging product","","SKU-"+suffix));
        UUID sku = product.skus().getFirst().id(), warehouse = UUID.randomUUID();
        jdbc.update("insert into operationsflow.warehouse(id,code,name,address_line,city,postal_code,country_code,status,created_at,updated_at) values (?,?,'Synthetic','Example 1','Berlin','10115','DE','ACTIVE',now(),now())", warehouse, "W-"+suffix);
        inventory.receive(UUID.randomUUID(), new InventoryContracts.Receipt(warehouse,sku,10L,"Synthetic receipt"));
        return new Fixture(customer.id(),sku,warehouse);
    }
    OrderContracts.Create request(Fixture f) { return new OrderContracts.Create(f.customer(), List.of(new OrderContracts.Line(f.sku(),3))); }
    OrderContracts.View create() { return orders.create(UUID.randomUUID(), request(fixture())); }
    void ready(UUID order) {
        for (var task : fulfillmentQueries.order(order).tasks()) {
            for (var line : task.lines()) task = fulfillment.pick(task.id(),line.skuId(),new Pick(line.quantity(),task.version()));
            task = fulfillment.pack(task.id(),new Pack(task.version(),task.lines().stream().map(l -> new VerifiedLine(l.skuId(),l.quantity())).toList()));
            fulfillment.shipment(task.id(), new PrepareShipment(task.version(),"Synthetic carrier","TRACK-"+task.id()));
        }
    }
    long count(String table) { return jdbc.queryForObject("select count(*) from operationsflow."+table,Long.class); }
    String state(UUID event) { return jdbc.queryForObject("select state from operationsflow.event_outbox where event_id=?",String.class,event); }
    <T> List<T> concurrent(int n, IntFunction<T> operation) throws Exception {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var start = new CountDownLatch(1); var ready = new CountDownLatch(n);
        try (var pool = Executors.newFixedThreadPool(n)) {
            var futures = new ArrayList<Future<T>>();
            for (int i=0;i<n;i++) { final int index=i; futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try { ready.countDown(); start.await(); return operation.apply(index); }
                finally { SecurityContextHolder.clearContext(); }
            })); }
            boolean all = ready.await(10,TimeUnit.SECONDS); start.countDown(); assertThat(all).isTrue();
            var result = new ArrayList<T>(); for (var future : futures) result.add(future.get(30,TimeUnit.SECONDS)); return result;
        }
    }
}

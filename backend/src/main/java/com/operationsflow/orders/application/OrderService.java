package com.operationsflow.orders.application;

import java.util.*;
import java.security.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.inventory.application.*;
import com.operationsflow.inventory.api.InventoryContracts.Terminal;
import com.operationsflow.inventory.domain.OrderAllocator;
import com.operationsflow.orders.api.OrderContracts.*;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.orders.persistence.OrderStore;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Validated
@Transactional(readOnly = true)
public class OrderService {
    private final com.operationsflow.audit.application.AuditService audit;
    private final com.operationsflow.fulfillment.application.FulfillmentPlanning fulfillment;
    private final com.operationsflow.messaging.application.OrderEvents events;
    private final OrderStore orders;
    private final CustomerService customers;
    private final OrderInventoryAccess allocation;
    private final InventoryService inventory;
    private final ObjectMapper json;
    public OrderService(OrderStore orders, CustomerService customers, OrderInventoryAccess allocation, InventoryService inventory, ObjectMapper json, com.operationsflow.audit.application.AuditService audit, com.operationsflow.fulfillment.application.FulfillmentPlanning fulfillment, com.operationsflow.messaging.application.OrderEvents events) {
        this.events = events;
        this.fulfillment = fulfillment;
        this.audit = audit;
        this.orders = orders; this.customers = customers; this.allocation = allocation; this.inventory = inventory; this.json = json;
    }
    @Transactional
    public View create(@NotNull UUID key, @NotNull @Valid Create request) {
        var lines = request.lines().stream().sorted(Comparator.comparing(l -> l.skuId().toString())).toList();
        if (lines.stream().map(Line::skuId).distinct().count() != lines.size()) throw new BusinessException("INVALID_REQUEST","Combine duplicate SKU lines before submitting an order.");
        var canonical = new Create(request.customerId(),lines);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
            var claim = orders.claim(key,hash);
            if (!claim.hash().equals(hash)) throw new BusinessException("IDEMPOTENCY_CONFLICT","This idempotency key belongs to a different order request.");
            if (claim.response() != null) return json.readValue(claim.response(),View.class);
            var customer = customers.lockActiveForOrder(request.customerId());
            UUID id = UUID.randomUUID();
            var stock = allocation.reserve(id,lines.stream().map(l -> new OrderAllocator.Demand(l.skuId(),l.quantity())).toList());
            orders.insert(id,customer,canonical,stock); fulfillment.plan(id); audit.record("ORDER_CREATED",id);
            var response = orders.get(id); events.record(id,com.operationsflow.messaging.domain.OrderEvent.Type.ORDER_RESERVED,response.totalUnits()); orders.complete(key,json.writeValueAsString(response)); return response;
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) { throw new IllegalStateException("Cannot serialize order",ex); }
    }
    public View get(UUID id) { return orders.get(id); }
    public PageResponse<Summary> list(String query, UUID customerId, OrderStatus status, int page, int size, String sort, String direction) {
        return orders.list(query,customerId,status,page,size,sort,direction);
    }
    @Transactional
    public View cancel(UUID id, @NotNull @Valid Cancel request) {
        orders.lock(id); var order = orders.get(id);
        if (!order.status().cancel()) return order;
        inventory.releaseForOrder(id,order.reservationId(),new Terminal(request.reason()));
        fulfillment.cancel(id); orders.cancel(id,request.reason()); audit.record("ORDER_CANCELLED",id); events.record(id,com.operationsflow.messaging.domain.OrderEvent.Type.ORDER_CANCELLED,order.totalUnits()); return orders.get(id);
    }
}

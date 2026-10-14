package com.operationsflow.orders.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.orders.application.OrderService;
import com.operationsflow.orders.api.OrderContracts.*;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Unpriced orders with atomic allocation. Independent warehouse preparation with whole-order dispatch. States: RESERVED, PROCESSING, READY_TO_SHIP, SHIPPED, CANCELLED.")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    @PostMapping
    @Operation(summary = "Create and reserve an order atomically", description = "Requires a UUID Idempotency-Key. Reordered lines replay the original 201 response; a changed payload returns 409. Failed transactions do not retain the key. Allocation prefers one warehouse, then splits by warehouse code. Insufficient stock returns 409 with no effects. Prices, currency and monetary totals are explicitly deferred.")
    public ResponseEntity<View> create(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Create request) {
        var order = service.create(key,request);
        return ResponseEntity.created(URI.create("/api/v1/orders/"+order.id())).body(order);
    }
    @GetMapping
    @Operation(summary = "Search orders", description = "Literal substring search on order number and customer snapshot name/reference. Optional customerId/status. Zero-based page, size 1–100. Sort createdAt, orderNumber, customerName or status; direction asc/desc.")
    public PageResponse<Summary> list(@RequestParam(defaultValue="") String query, @RequestParam(required=false) UUID customerId,
        @RequestParam(required=false) OrderStatus status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="createdAt") String sort, @RequestParam(defaultValue="desc") String direction) {
        return service.list(query,customerId,status,page,size,sort,direction);
    }
    @GetMapping("/{id}") @Operation(summary="Read order snapshots and allocations")
    public View get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/cancel")
    @Operation(summary="Cancel an unshipped order", description="Locks order and reservation; releases each allocation exactly once in the same transaction. A repeated cancellation returns the current cancelled order, preserving the first reason. Prepared fulfillment tasks and shipments are cancelled in the same transaction; SHIPPED returns 409.")
    public View cancel(@PathVariable UUID id, @Valid @RequestBody Cancel request) { return service.cancel(id,request); }
}

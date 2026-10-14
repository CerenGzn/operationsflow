package com.operationsflow.inventory.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.inventory.api.InventoryContracts.*;
import com.operationsflow.inventory.application.*;
import com.operationsflow.inventory.domain.ReservationState;
import com.operationsflow.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Transactional stock ledger. Quantities are integer units. Receipt, adjustment and reservation creation require a UUID Idempotency-Key. Same key/payload replays the original response; changed payload returns 409. No authenticated actor exists yet.")
public class InventoryController {
    private final InventoryService service;
    private final InventoryQueries queries;
    public InventoryController(InventoryService service, InventoryQueries queries) { this.service = service; this.queries = queries; }
    @GetMapping("/balances") @Operation(summary = "List warehouse/SKU balances", description = "available = onHand - reserved; missing combinations imply zero. Optional warehouseId/skuId filters. Zero-based page, size 1–100.")
    public PageResponse<BalanceView> balances(@RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) UUID skuId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.balances(warehouseId, skuId, page, size);
    }
    @GetMapping("/movements") @Operation(summary = "List immutable stock history, newest first", description = "Every committed stock effect has one movement per SKU/warehouse, with before-to-after deltas, reason, operation ID and truthful unauthenticated actor marker.")
    public PageResponse<MovementView> movements(@RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) UUID skuId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.movements(warehouseId, skuId, page, size);
    }
    @GetMapping("/movements/{id}") public MovementView movement(@PathVariable UUID id) { return queries.movement(id); }
    @PostMapping("/receipts") @Operation(summary = "Receive positive stock", description = "Requires active product and warehouse. Returns 201 with immutable movement; retries return the same original response.")
    public ResponseEntity<MovementView> receive(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Receipt request) {
        var result = service.receive(key, request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + result.id())).body(result);
    }
    @PostMapping("/adjustments") @Operation(summary = "Apply a signed stock correction", description = "Delta cannot be zero or reduce on-hand below reserved. Reason required. Maximum absolute delta 1 billion; resulting stock maximum 1 trillion.")
    public ResponseEntity<MovementView> adjust(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Adjustment request) {
        var result = service.adjust(key, request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + result.id())).body(result);
    }
    @PostMapping("/reservations") @Operation(summary = "Atomically reserve explicit warehouse/SKU lines", description = "1–50 distinct lines; unique opaque ownerReference (future order ID). All lines succeed or all roll back. Sorted by warehouse/SKU for locking. This endpoint does not allocate warehouses automatically.")
    public ResponseEntity<ReservationView> reserve(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Reserve request) {
        var result = service.reserve(key, request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/reservations/" + result.id())).body(result);
    }
    @GetMapping("/reservations") @Operation(summary = "List reservation groups by state", description = "Newest first; page zero-based, size 1–100. Lines are immutable; owners remain reserved after terminal transitions.")
    public PageResponse<ReservationView> reservations(@RequestParam(required = false) ReservationState state,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.reservations(state, page, size);
    }
    @GetMapping("/reservations/{id}") public ReservationView reservation(@PathVariable UUID id) { return queries.reservation(id); }
    @PostMapping("/reservations/{id}/release") @Operation(summary = "Release stock exactly once", description = "ACTIVE -> RELEASED. Repeating RELEASED returns current reservation without another stock effect. CONSUMED returns 409. No separate idempotency key is needed.")
    public ReservationView release(@PathVariable UUID id, @Valid @RequestBody Terminal request) { return service.release(id, request); }
    @PostMapping("/reservations/{id}/consume") @Operation(summary = "Consume reserved stock exactly once", description = "Inventory fulfillment primitive only: ACTIVE -> CONSUMED deducts both on-hand and reserved. Repeating CONSUMED is a no-op; RELEASED returns 409. Future orders must invoke this in their transaction.")
    public ReservationView consume(@PathVariable UUID id, @Valid @RequestBody Terminal request) { return service.consume(id, request); }
}

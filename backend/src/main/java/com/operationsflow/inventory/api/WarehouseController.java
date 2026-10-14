package com.operationsflow.inventory.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.inventory.application.WarehouseService;
import com.operationsflow.inventory.api.WarehouseContracts.*;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.RecordStatus;

@RestController
@RequestMapping("/api/v1/warehouses")
@Tag(name = "Warehouses", description = "Warehouse metadata with immutable codes; no stock operations. Deactivation is permanent in this milestone.")
public class WarehouseController {
    private final WarehouseService service;
    public WarehouseController(WarehouseService service) { this.service = service; }
    @PostMapping @Operation(summary = "Create a warehouse", description = "Uppercase code is globally unique; countryCode is two uppercase letters. Returns 201 or 409 for a duplicate code.")
    public ResponseEntity<View> create(@Valid @RequestBody Create request) {
        var warehouse = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + warehouse.id())).body(warehouse);
    }
    @GetMapping @Operation(summary = "Search warehouses by name, code or city", description = "Zero-based page; size 1–100; sort name, code, city, createdAt or status; direction asc or desc. Literal, case-insensitive substring search.")
    public PageResponse<View> list(@RequestParam(defaultValue = "") String query, @RequestParam(required = false) RecordStatus status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return PageResponse.from(service.list(query, status, page, size, sort, direction));
    }
    @GetMapping("/{id}") public View get(@PathVariable UUID id) { return service.get(id); }
    @PutMapping("/{id}") @Operation(summary = "Edit active warehouse metadata", description = "Requires current version; stale version and inactive records return 409. Code is immutable.")
    public View update(@PathVariable UUID id, @Valid @RequestBody Update request) { return service.update(id, request); }
    @PostMapping("/{id}/deactivate") @Operation(summary = "Deactivate a warehouse", description = "Requires current version on first transition; repeated calls are safe. Code remains reserved.")
    public View deactivate(@PathVariable UUID id, @Valid @RequestBody Deactivate request) { return service.deactivate(id, request); }
}

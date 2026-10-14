package com.operationsflow.catalog.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.catalog.application.ProductService;
import com.operationsflow.catalog.api.ProductContracts.*;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.RecordStatus;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Catalog records with immutable globally unique SKU codes. Deactivation is permanent in this milestone.")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }
    @PostMapping @Operation(summary = "Create a product and its first SKU", description = "Returns 201; duplicate SKU returns 409. All writes are atomic.")
    public ResponseEntity<View> create(@Valid @RequestBody Create request) {
        var product = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id())).body(product);
    }
    @GetMapping @Operation(summary = "Search products by name or SKU", description = "Zero-based page; size 1–100; sort name, createdAt or status; direction asc or desc. Search is literal, case-insensitive substring matching.")
    public PageResponse<View> list(@RequestParam(defaultValue = "") String query, @RequestParam(required = false) RecordStatus status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return PageResponse.from(service.list(query, status, page, size, sort, direction));
    }
    @GetMapping("/{id}") public View get(@PathVariable UUID id) { return service.get(id); }
    @PutMapping("/{id}") @Operation(summary = "Edit active product details", description = "Requires current version. SKU identifiers cannot be updated. Stale version or inactive product returns 409.")
    public View update(@PathVariable UUID id, @Valid @RequestBody Update request) { return service.update(id, request); }
    @PostMapping("/{id}/skus") @Operation(summary = "Add an immutable SKU to an active product", description = "Requires current product version. Returns the updated product with 201. Duplicate codes return 409.")
    public ResponseEntity<View> addSku(@PathVariable UUID id, @Valid @RequestBody AddSku request) {
        return ResponseEntity.status(201).body(service.addSku(id, request));
    }
    @PostMapping("/{id}/deactivate") @Operation(summary = "Deactivate a product", description = "Requires current version on first transition. Repeated deactivation succeeds without changes. Existing identifiers remain reserved.")
    public View deactivate(@PathVariable UUID id, @Valid @RequestBody Deactivate request) { return service.deactivate(id, request); }
}

package com.operationsflow.customers.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.customers.application.CustomerService;
import com.operationsflow.customers.api.CustomerContracts.*;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.RecordStatus;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer business accounts with immutable reference numbers and synthetic shipping addresses. Deactivation is permanent in this milestone.")
public class CustomerController {
    private final CustomerService service;
    public CustomerController(CustomerService service) { this.service = service; }
    @PostMapping @Operation(summary = "Create a customer", description = "Uppercase referenceNumber is globally unique; countryCode is two uppercase letters. Returns 201 or 409 for a duplicate reference.")
    public ResponseEntity<View> create(@Valid @RequestBody Create request) {
        var customer = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id())).body(customer);
    }
    @GetMapping @Operation(summary = "Search customers by name, reference number or city", description = "Zero-based page; size 1–100; sort name, referenceNumber, city, createdAt or status; direction asc or desc. Literal, case-insensitive substring search.")
    public PageResponse<View> list(@RequestParam(defaultValue = "") String query, @RequestParam(required = false) RecordStatus status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String direction) {
        return PageResponse.from(service.list(query, status, page, size, sort, direction));
    }
    @GetMapping("/{id}") public View get(@PathVariable UUID id) { return service.get(id); }
    @PutMapping("/{id}") @Operation(summary = "Edit active customer metadata", description = "Requires current version; stale version and inactive records return 409. Reference number is immutable.")
    public View update(@PathVariable UUID id, @Valid @RequestBody Update request) { return service.update(id, request); }
    @PostMapping("/{id}/deactivate") @Operation(summary = "Deactivate a customer", description = "Requires current version on first transition; repeated calls are safe. Reference remains reserved; open orders block deactivation.")
    public View deactivate(@PathVariable UUID id, @Valid @RequestBody Deactivate request) { return service.deactivate(id, request); }
}

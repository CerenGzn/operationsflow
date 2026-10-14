package com.operationsflow.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.operationsflow.orders.domain.OrderStatus;

public final class OrderContracts {
    private OrderContracts() {}
    public record Line(@NotNull UUID skuId, @Min(1) @Max(1_000_000_000) long quantity) {}
    public record Create(@NotNull UUID customerId, @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines) {}
    public record Cancel(@NotBlank @Size(max = 500) String reason) { public Cancel { reason = reason == null ? null : reason.strip(); } }
    public record CustomerSnapshot(UUID id, String referenceNumber, String name, String addressLine, String city, String postalCode, String countryCode) {}
    public record LineView(UUID skuId, String skuCode, String productName, long quantity) {}
    public record AllocationView(UUID skuId, UUID warehouseId, String warehouseCode, long quantity) {}
    public record View(UUID id, String orderNumber, OrderStatus status, long version, CustomerSnapshot customer,
        List<LineView> lines, List<AllocationView> allocations, UUID reservationId, long totalUnits,
        String pricingStatus, BigDecimal totalAmount, String currency, String cancellationReason, Instant createdAt, Instant updatedAt) {}
    public record Summary(UUID id, String orderNumber, String customerName, OrderStatus status, long totalUnits,
        String pricingStatus, Instant createdAt) {}
}

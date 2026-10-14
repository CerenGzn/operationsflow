package com.operationsflow.inventory.api;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.operationsflow.inventory.domain.ReservationState;

public final class InventoryContracts {
    private InventoryContracts() {}
    public record Receipt(@NotNull UUID warehouseId, @NotNull UUID skuId,
        @Min(1) @Max(1_000_000_000) long quantity, @NotBlank @Size(max = 500) String reason) {
        public Receipt { reason = trim(reason); }
    }
    public record Adjustment(@NotNull UUID warehouseId, @NotNull UUID skuId,
        @Min(-1_000_000_000) @Max(1_000_000_000) long delta, @NotBlank @Size(max = 500) String reason) {
        public Adjustment { reason = trim(reason); }
    }
    public record Line(@NotNull UUID warehouseId, @NotNull UUID skuId, @Min(1) @Max(1_000_000_000) long quantity) {}
    public record Reserve(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") String ownerReference,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines, @NotBlank @Size(max = 500) String reason) {
        public Reserve { reason = trim(reason); }
    }
    public record Terminal(@NotBlank @Size(max = 500) String reason) { public Terminal { reason = trim(reason); } }
    public record BalanceView(UUID warehouseId, String warehouseCode, UUID skuId, String skuCode, String productName,
        long onHand, long reserved, long available, Instant updatedAt) {}
    public record MovementView(UUID id, UUID warehouseId, UUID skuId, String warehouseCode, String skuCode,
        String type, long onHandDelta, long reservedDelta, long onHandAfter, long reservedAfter,
        UUID reservationId, UUID operationId, String reason, String actor, Instant createdAt) {}
    public record ReservationView(UUID id, String ownerReference, UUID orderId, ReservationState state, List<Line> lines,
        Instant createdAt, Instant updatedAt) {}
    private static String trim(String value) { return value == null ? null : value.strip(); }
}

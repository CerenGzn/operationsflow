package com.operationsflow.catalog.api;

import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import com.operationsflow.shared.domain.RecordStatus;

public final class ProductContracts {
    private ProductContracts() {}
    public record Create(
        @NotBlank @Size(max = 160) String name,
        @NotNull @Size(max = 2000) String description,
        @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") @Schema(description = "Immutable, globally unique SKU code", example = "CHAIR-BLUE") String skuCode) {
        public Create { name = name == null ? null : name.strip(); }
    }
    public record Update(@NotBlank @Size(max = 160) String name, @NotNull @Size(max = 2000) String description,
                         @NotNull @PositiveOrZero Long version) {
        public Update { name = name == null ? null : name.strip(); }
    }
    public record AddSku(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
                         @NotNull @PositiveOrZero Long version) {}
    public record Deactivate(@NotNull @PositiveOrZero Long version) {}
    public record SkuView(UUID id, String code) {}
    public record View(UUID id, String name, String description, RecordStatus status, long version,
                       Instant createdAt, Instant updatedAt, List<SkuView> skus) {}
}

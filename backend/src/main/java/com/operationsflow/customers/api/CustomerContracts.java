package com.operationsflow.customers.api;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.operationsflow.shared.domain.RecordStatus;

public final class CustomerContracts {
    private CustomerContracts() {}
    public record Create(
        @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,31}") String referenceNumber,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 200) String addressLine,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String countryCode) {
        public Create {
            name = trim(name); addressLine = trim(addressLine); city = trim(city); postalCode = trim(postalCode);
        }
    }
    public record Update(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 200) String addressLine,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        @NotNull @Pattern(regexp = "[A-Z]{2}") String countryCode,
        @NotNull @PositiveOrZero Long version) {
        public Update {
            name = trim(name); addressLine = trim(addressLine); city = trim(city); postalCode = trim(postalCode);
        }
    }
    public record Deactivate(@NotNull @PositiveOrZero Long version) {}
    public record View(UUID id, String referenceNumber, String name, String addressLine, String city, String postalCode,
                       String countryCode, RecordStatus status, long version, Instant createdAt, Instant updatedAt) {}
    private static String trim(String value) { return value == null ? null : value.strip(); }
}

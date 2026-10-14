package com.operationsflow.inventory.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.operationsflow.shared.domain.*;

@Entity
@Table(name = "warehouse", schema = "operationsflow")
public class Warehouse {
    @Id private UUID id;
    @Column(nullable = false, unique = true, updatable = false, length = 32) private String code;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 200) private String addressLine;
    @Column(nullable = false, length = 100) private String city;
    @Column(nullable = false, length = 20) private String postalCode;
    @Column(nullable = false, length = 2) private String countryCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private RecordStatus status;
    @Version private long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected Warehouse() {}
    public Warehouse(String code, String name, String addressLine, String city, String postalCode, String countryCode) {
        this.id = UUID.randomUUID(); this.code = code; this.name = name; this.addressLine = addressLine;
        this.city = city; this.postalCode = postalCode; this.countryCode = countryCode;
        this.status = RecordStatus.ACTIVE; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public void update(String name, String addressLine, String city, String postalCode, String countryCode, long expected) {
        checkVersion(expected);
        if (status != RecordStatus.ACTIVE) throw new BusinessException("INACTIVE_RECORD", "Inactive warehouses cannot be edited.");
        this.name = name; this.addressLine = addressLine; this.city = city;
        this.postalCode = postalCode; this.countryCode = countryCode; this.updatedAt = Instant.now();
    }
    public void deactivate(long expected) {
        if (status == RecordStatus.INACTIVE) return;
        checkVersion(expected); status = RecordStatus.INACTIVE; updatedAt = Instant.now();
    }
    private void checkVersion(long expected) {
        if (version != expected) throw new BusinessException("STALE_VERSION", "This warehouse changed. Reload it before saving.");
    }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getAddressLine() { return addressLine; }
    public String getCity() { return city; }
    public String getPostalCode() { return postalCode; }
    public String getCountryCode() { return countryCode; }
    public RecordStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

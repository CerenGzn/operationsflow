package com.operationsflow.catalog.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.operationsflow.shared.domain.*;

@Entity
@Table(name = "product", schema = "operationsflow")
public class Product {
    @Id private UUID id;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private RecordStatus status;
    @Version private long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected Product() {}
    public Product(String name, String description) {
        this.id = UUID.randomUUID(); this.name = name; this.description = description;
        this.status = RecordStatus.ACTIVE; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public void checkVersion(long expected) {
        if (version != expected) throw new BusinessException("STALE_VERSION", "This product changed. Reload it before saving.");
    }
    public void requireActive() {
        if (status != RecordStatus.ACTIVE) throw new BusinessException("INACTIVE_RECORD", "Inactive products cannot be edited or receive new SKUs.");
    }
    public void update(String name, String description, long expected) {
        checkVersion(expected); requireActive(); this.name = name; this.description = description; updatedAt = Instant.now();
    }
    public void touch() { requireActive(); updatedAt = Instant.now(); }
    public void deactivate(long expected) {
        if (status == RecordStatus.INACTIVE) return;
        checkVersion(expected); status = RecordStatus.INACTIVE; updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public RecordStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

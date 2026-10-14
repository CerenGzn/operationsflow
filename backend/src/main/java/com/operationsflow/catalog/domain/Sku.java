package com.operationsflow.catalog.domain;

import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "sku", schema = "operationsflow")
public class Sku {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID productId;
    @Column(nullable = false, unique = true, updatable = false, length = 64) private String code;
    protected Sku() {}
    public Sku(UUID productId, String code) { this.id = UUID.randomUUID(); this.productId = productId; this.code = code; }
    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public String getCode() { return code; }
}

package com.operationsflow.inventory.application;

import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.operationsflow.catalog.application.ProductDeactivationPolicy;
import com.operationsflow.inventory.persistence.InventoryStore;
import com.operationsflow.shared.domain.BusinessException;

@Component
public class InventoryDeactivationPolicy implements ProductDeactivationPolicy {
    private final InventoryStore store;
    public InventoryDeactivationPolicy(InventoryStore store) { this.store = store; }
    @Override public void requireNoStock(Collection<UUID> skuIds) {
        if (store.hasSkuStock(skuIds)) throw new BusinessException("STOCK_REMAINS", "Remove on-hand stock and resolve reservations before deactivating this product.");
    }
    public void requireEmptyWarehouse(UUID id) {
        if (store.hasWarehouseStock(id)) throw new BusinessException("STOCK_REMAINS", "Remove on-hand stock and resolve reservations before deactivating this warehouse.");
    }
}

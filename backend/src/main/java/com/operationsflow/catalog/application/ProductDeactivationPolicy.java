package com.operationsflow.catalog.application;

import java.util.Collection;
import java.util.UUID;

/** Catalog-owned port: callers hold the product lock before consulting inventory. */
public interface ProductDeactivationPolicy {
    void requireNoStock(Collection<UUID> skuIds);
}

package com.operationsflow.inventory.domain;

import java.util.Comparator;
import java.util.UUID;

public record StockLine(UUID warehouseId, UUID skuId, long quantity) {
    // UUID strings sort in the same order as PostgreSQL UUIDs (unlike UUID.compareTo).
    public static final Comparator<StockLine> LOCK_ORDER = Comparator
        .comparing((StockLine line) -> line.warehouseId().toString()).thenComparing(line -> line.skuId().toString());
}

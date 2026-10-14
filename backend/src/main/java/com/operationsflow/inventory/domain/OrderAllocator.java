package com.operationsflow.inventory.domain;

import java.util.*;
import com.operationsflow.shared.domain.BusinessException;

/** Business priority is warehouse code, independent of the UUID database lock order. */
public final class OrderAllocator {
    private OrderAllocator() {}
    public record Warehouse(UUID id, String code) {}
    public record Demand(UUID skuId, long quantity) {}
    public static List<StockLine> allocate(List<Demand> demand, List<Warehouse> warehouses, List<StockLine> available) {
        var priority = warehouses.stream().sorted(Comparator.comparing(Warehouse::code).thenComparing(w -> w.id().toString())).toList();
        record Key(UUID warehouse, UUID sku) {}
        var stock = new HashMap<Key, Long>();
        available.forEach(l -> stock.put(new Key(l.warehouseId(), l.skuId()), l.quantity()));
        for (var warehouse : priority) {
            if (demand.stream().allMatch(d -> stock.getOrDefault(new Key(warehouse.id(), d.skuId()), 0L) >= d.quantity())) {
                return demand.stream().map(d -> new StockLine(warehouse.id(), d.skuId(), d.quantity())).sorted(StockLine.LOCK_ORDER).toList();
            }
        }
        var result = new ArrayList<StockLine>();
        for (var d : demand) {
            long remaining = d.quantity();
            for (var warehouse : priority) {
                long taken = Math.min(remaining, stock.getOrDefault(new Key(warehouse.id(), d.skuId()), 0L));
                if (taken > 0) result.add(new StockLine(warehouse.id(), d.skuId(), taken));
                remaining -= taken;
                if (remaining == 0) break;
            }
            if (remaining > 0) throw new BusinessException("INSUFFICIENT_STOCK", "Insufficient available stock for SKU " + d.skuId() + ". No stock was reserved.");
        }
        return result.stream().sorted(StockLine.LOCK_ORDER).toList();
    }
}

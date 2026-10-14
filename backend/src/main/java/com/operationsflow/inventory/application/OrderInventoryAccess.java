package com.operationsflow.inventory.application;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.catalog.application.StockCatalogAccess;
import com.operationsflow.inventory.domain.*;
import com.operationsflow.inventory.persistence.InventoryStore;

/** Order-facing inventory boundary. Every effect joins the caller's order transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderInventoryAccess {
    private final StockCatalogAccess catalog;
    private final InventoryStore store;
    public OrderInventoryAccess(StockCatalogAccess catalog, InventoryStore store) { this.catalog = catalog; this.store = store; }
    public record Allocation(UUID skuId, UUID warehouseId, String warehouseCode, long quantity) {}
    public record Reserved(UUID reservationId, List<StockCatalogAccess.Snapshot> skus, List<Allocation> allocations) {}
    public Reserved reserve(UUID orderId, List<OrderAllocator.Demand> demand) {
        store.limitLockWait();
        UUID reservationId = UUID.randomUUID();
        store.insertOrderReservation(reservationId, orderId);
        var skuIds = demand.stream().map(OrderAllocator.Demand::skuId).toList();
        var snapshots = catalog.lockOrderSkus(skuIds);
        var warehouses = store.lockActiveWarehouses();
        var available = store.lockAvailable(skuIds, warehouses.stream().map(InventoryStore.WarehouseOption::id).toList());
        var lines = OrderAllocator.allocate(demand, warehouses.stream().map(w -> new OrderAllocator.Warehouse(w.id(), w.code())).toList(), available);
        var codes = new HashMap<UUID, String>(); warehouses.forEach(w -> codes.put(w.id(), w.code()));
        UUID operationId = UUID.randomUUID();
        for (var line : lines) {
            var before = store.lockBalance(line);
            store.insertLine(reservationId, line);
            store.write(line, before, before.reserve(line.quantity()), "RESERVATION", reservationId, operationId, "Order " + orderId);
        }
        return new Reserved(reservationId, snapshots, lines.stream().map(l -> new Allocation(l.skuId(), l.warehouseId(), codes.get(l.warehouseId()), l.quantity())).toList());
    }
}

package com.operationsflow.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.operationsflow.catalog.application.StockCatalogAccess;
import com.operationsflow.inventory.api.InventoryContracts.*;
import com.operationsflow.inventory.domain.*;
import com.operationsflow.inventory.persistence.InventoryStore;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Transactional
public class InventoryService {
    private final StockCatalogAccess catalog;
    private final InventoryStore store;
    private final InventoryQueries queries;
    private final ObjectMapper json;
    public InventoryService(StockCatalogAccess catalog, InventoryStore store, InventoryQueries queries, ObjectMapper json) {
        this.catalog = catalog; this.store = store; this.queries = queries; this.json = json;
    }
    public MovementView receive(UUID key, Receipt request) {
        return once(key, "RECEIPT", request, MovementView.class, () -> {
            var line = new StockLine(request.warehouseId(), request.skuId(), request.quantity());
            guard(List.of(line)); var before = store.lockBalance(line);
            var id = store.write(line, before, before.receive(request.quantity()), "RECEIPT", null, key, request.reason());
            return queries.movement(id);
        });
    }
    public MovementView adjust(UUID key, Adjustment request) {
        return once(key, "ADJUSTMENT", request, MovementView.class, () -> {
            var line = new StockLine(request.warehouseId(), request.skuId(), request.delta());
            guard(List.of(line)); var before = store.lockBalance(line);
            var id = store.write(line, before, before.adjust(request.delta()), "ADJUSTMENT", null, key, request.reason());
            return queries.movement(id);
        });
    }
    public ReservationView reserve(UUID key, Reserve request) {
        var lines = request.lines().stream().map(l -> new StockLine(l.warehouseId(), l.skuId(), l.quantity())).sorted(StockLine.LOCK_ORDER).toList();
        for (int i = 1; i < lines.size(); i++) {
            if (StockLine.LOCK_ORDER.compare(lines.get(i - 1), lines.get(i)) == 0) {
                throw new BusinessException("INVALID_REQUEST", "Combine duplicate warehouse/SKU lines before reserving.");
            }
        }
        var canonical = new Reserve(request.ownerReference(), lines.stream().map(l -> new Line(l.warehouseId(), l.skuId(), l.quantity())).toList(), request.reason());
        return once(key, "RESERVATION", canonical, ReservationView.class, () -> {
            UUID id = UUID.randomUUID(); store.insertReservation(id, request.ownerReference());
            guard(lines);
            // Process every balance in the same order. A later failure rolls back earlier lines, ledger and owner claim.
            for (var line : lines) {
                var before = store.lockBalance(line); var after = before.reserve(line.quantity());
                store.insertLine(id, line); store.write(line, before, after, "RESERVATION", id, key, request.reason());
            }
            return queries.reservation(id);
        });
    }
    public ReservationView release(UUID id, Terminal request) { return finish(id, request, ReservationState.RELEASED); }
    /** Inventory-only fulfillment primitive; Milestone 4 will call inside the order transaction. */
    public ReservationView consume(UUID id, Terminal request) { return finish(id, request, ReservationState.CONSUMED); }
    private ReservationView finish(UUID id, Terminal request, ReservationState target) {
        store.limitLockWait();
        var state = store.lockReservation(id);
        if (!state.transitionTo(target)) return queries.reservation(id);
        var lines = store.lines(id); guard(lines); UUID operation = UUID.randomUUID();
        for (var line : lines) {
            var before = store.lockBalance(line);
            var after = target == ReservationState.RELEASED ? before.release(line.quantity()) : before.consume(line.quantity());
            store.write(line, before, after, target == ReservationState.RELEASED ? "RELEASE" : "FULFILLMENT", id, operation, request.reason());
        }
        store.transition(id, target); return queries.reservation(id);
    }
    private void guard(List<StockLine> lines) {
        catalog.lockActiveSkus(lines.stream().map(StockLine::skuId).toList());
        store.lockWarehouses(lines.stream().map(StockLine::warehouseId).toList());
    }
    private <T> T once(UUID key, String operation, Object request, Class<T> responseType, Supplier<T> action) {
        store.limitLockWait();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
            var claimed = store.claim(key, operation, hash);
            if (!claimed.operation().equals(operation) || !claimed.hash().equals(hash)) {
                throw new BusinessException("IDEMPOTENCY_CONFLICT", "This idempotency key was used with another operation or payload.");
            }
            if (claimed.response() != null) return json.readValue(claimed.response(), responseType);
            var response = action.get(); store.complete(key, json.writeValueAsString(response)); return response;
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot serialize inventory operation", ex);
        }
    }
}

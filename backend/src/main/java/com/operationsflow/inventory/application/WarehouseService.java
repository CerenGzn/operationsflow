package com.operationsflow.inventory.application;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import com.operationsflow.inventory.api.WarehouseContracts.*;
import com.operationsflow.inventory.domain.Warehouse;
import com.operationsflow.inventory.persistence.WarehouseRepository;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.*;

@Service
@Transactional(readOnly = true)
public class WarehouseService {
    private final WarehouseRepository warehouses;
    public WarehouseService(WarehouseRepository warehouses) { this.warehouses = warehouses; }
    public Page<View> list(String query, RecordStatus status, int page, int size, String sort, String direction) {
        String term = ListQuery.search(query).toLowerCase(Locale.ROOT);
        var pageable = ListQuery.page(page, size, sort, direction, Set.of("name", "code", "city", "createdAt", "status"));
        Specification<Warehouse> spec = (root, criteria, cb) -> {
            var search = term.isEmpty() ? cb.conjunction() : cb.or(
                cb.greaterThan(cb.locate(cb.lower(root.get("name")), term), 0),
                cb.greaterThan(cb.locate(cb.lower(root.get("code")), term), 0),
                cb.greaterThan(cb.locate(cb.lower(root.get("city")), term), 0));
            return status == null ? search : cb.and(search, cb.equal(root.get("status"), status));
        };
        return warehouses.findAll(spec, pageable).map(this::view);
    }
    public View get(UUID id) { return view(find(id)); }
    @Transactional
    public View create(Create r) {
        return view(warehouses.saveAndFlush(new Warehouse(r.code(), r.name(), r.addressLine(), r.city(), r.postalCode(), r.countryCode())));
    }
    @Transactional
    public View update(UUID id, Update r) {
        var warehouse = find(id);
        warehouse.update(r.name(), r.addressLine(), r.city(), r.postalCode(), r.countryCode(), r.version());
        warehouses.flush(); return view(warehouse);
    }
    @Transactional
    public View deactivate(UUID id, Deactivate r) {
        var warehouse = find(id); warehouse.deactivate(r.version()); warehouses.flush(); return view(warehouse);
    }
    private Warehouse find(UUID id) {
        return warehouses.findById(id).orElseThrow(() -> new BusinessException("NOT_FOUND", "Warehouse not found."));
    }
    private View view(Warehouse w) {
        return new View(w.getId(), w.getCode(), w.getName(), w.getAddressLine(), w.getCity(), w.getPostalCode(),
            w.getCountryCode(), w.getStatus(), w.getVersion(), w.getCreatedAt(), w.getUpdatedAt());
    }
}

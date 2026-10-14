package com.operationsflow.catalog.application;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.operationsflow.shared.domain.BusinessException;

/** Public catalog boundary for inventory writes; no catalog repository escapes. */
@Service
public class StockCatalogAccess {
    private final NamedParameterJdbcTemplate jdbc;
    public StockCatalogAccess(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActiveSkus(Collection<UUID> skuIds) {
        var ids = new HashSet<>(skuIds);
        var products = jdbc.query("select id, product_id from operationsflow.sku where id in (:ids)",
            Map.of("ids", ids), (rs, n) -> rs.getObject("product_id", UUID.class));
        if (products.size() != ids.size()) throw new BusinessException("NOT_FOUND", "One or more SKUs do not exist.");
        var statuses = jdbc.query("select status from operationsflow.product where id in (:ids) order by id for update",
            Map.of("ids", new HashSet<>(products)), (rs, n) -> rs.getString("status"));
        if (statuses.stream().anyMatch(s -> !s.equals("ACTIVE"))) {
            throw new BusinessException("INACTIVE_RECORD", "Inventory cannot change for an inactive product/SKU.");
        }
    }
}

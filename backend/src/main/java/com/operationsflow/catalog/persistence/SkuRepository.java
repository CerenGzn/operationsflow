package com.operationsflow.catalog.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import com.operationsflow.catalog.domain.Sku;

public interface SkuRepository extends JpaRepository<Sku, UUID> {
    List<Sku> findByProductIdInOrderByCodeAsc(Collection<UUID> productIds);
}

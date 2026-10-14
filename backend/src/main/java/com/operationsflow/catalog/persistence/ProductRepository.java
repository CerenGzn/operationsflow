package com.operationsflow.catalog.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import com.operationsflow.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {}

package com.operationsflow.catalog.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import com.operationsflow.catalog.domain.Product;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    java.util.Optional<Product> findLockedById(UUID id);
}

package com.operationsflow.inventory.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import com.operationsflow.inventory.domain.Warehouse;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.id = :id")
    java.util.Optional<Warehouse> findLockedById(UUID id);
}

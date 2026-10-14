package com.operationsflow.inventory.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import com.operationsflow.inventory.domain.Warehouse;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {}

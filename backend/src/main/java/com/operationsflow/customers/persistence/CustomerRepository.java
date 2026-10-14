package com.operationsflow.customers.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import com.operationsflow.customers.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    java.util.Optional<Customer> findLockedById(UUID id);
}

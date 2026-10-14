package com.operationsflow.customers.application;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import com.operationsflow.customers.api.CustomerContracts.*;
import com.operationsflow.customers.domain.Customer;
import com.operationsflow.customers.persistence.CustomerRepository;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.*;

@Service
@Transactional(readOnly = true)
public class CustomerService {
    private final CustomerRepository customers;
    private final CustomerDeactivationPolicy deactivationPolicy;
    public CustomerService(CustomerRepository customers, CustomerDeactivationPolicy deactivationPolicy) {
        this.customers = customers; this.deactivationPolicy = deactivationPolicy;
    }
    public Page<View> list(String query, RecordStatus status, int page, int size, String sort, String direction) {
        String term = ListQuery.search(query).toLowerCase(Locale.ROOT);
        var pageable = ListQuery.page(page, size, sort, direction, Set.of("name", "referenceNumber", "city", "createdAt", "status"));
        Specification<Customer> spec = (root, criteria, cb) -> {
            var search = term.isEmpty() ? cb.conjunction() : cb.or(
                cb.greaterThan(cb.locate(cb.lower(root.get("name")), term), 0),
                cb.greaterThan(cb.locate(cb.lower(root.get("referenceNumber")), term), 0),
                cb.greaterThan(cb.locate(cb.lower(root.get("city")), term), 0));
            return status == null ? search : cb.and(search, cb.equal(root.get("status"), status));
        };
        return customers.findAll(spec, pageable).map(this::view);
    }
    public View get(UUID id) { return view(find(id)); }
    @Transactional
    public View create(Create r) {
        return view(customers.saveAndFlush(new Customer(r.referenceNumber(), r.name(), r.addressLine(), r.city(), r.postalCode(), r.countryCode())));
    }
    @Transactional
    public View update(UUID id, Update r) {
        var customer = find(id);
        customer.update(r.name(), r.addressLine(), r.city(), r.postalCode(), r.countryCode(), r.version());
        customers.flush(); return view(customer);
    }
    @Transactional
    public View deactivate(UUID id, Deactivate r) {
        var customer = customers.findLockedById(id).orElseThrow(() -> new BusinessException("NOT_FOUND", "Customer not found."));
        if (customer.getStatus() == RecordStatus.ACTIVE) deactivationPolicy.requireNoOpenOrders(id);
        customer.deactivate(r.version()); customers.flush(); return view(customer);
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public View lockActiveForOrder(UUID id) {
        var customer = customers.findLockedById(id).orElseThrow(() -> new BusinessException("NOT_FOUND", "Customer not found."));
        if (customer.getStatus() != RecordStatus.ACTIVE) throw new BusinessException("INACTIVE_RECORD", "Orders require an active customer.");
        return view(customer);
    }
    private Customer find(UUID id) {
        return customers.findById(id).orElseThrow(() -> new BusinessException("NOT_FOUND", "Customer not found."));
    }
    private View view(Customer w) {
        return new View(w.getId(), w.getReferenceNumber(), w.getName(), w.getAddressLine(), w.getCity(), w.getPostalCode(),
            w.getCountryCode(), w.getStatus(), w.getVersion(), w.getCreatedAt(), w.getUpdatedAt());
    }
}

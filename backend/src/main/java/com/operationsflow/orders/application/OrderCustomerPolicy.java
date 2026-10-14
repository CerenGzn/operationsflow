package com.operationsflow.orders.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.operationsflow.customers.application.CustomerDeactivationPolicy;
import com.operationsflow.orders.persistence.OrderStore;
import com.operationsflow.shared.domain.BusinessException;

@Component
public class OrderCustomerPolicy implements CustomerDeactivationPolicy {
    private final OrderStore orders;
    public OrderCustomerPolicy(OrderStore orders) { this.orders = orders; }
    @Override public void requireNoOpenOrders(UUID customerId) {
        if (orders.hasOpenOrders(customerId)) throw new BusinessException("OPEN_ORDERS","Cancel open orders before deactivating this customer.");
    }
}

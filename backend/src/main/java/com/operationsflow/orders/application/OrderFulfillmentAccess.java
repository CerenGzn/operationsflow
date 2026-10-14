package com.operationsflow.orders.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.orders.persistence.OrderStore;
import com.operationsflow.orders.api.OrderContracts.View;
import com.operationsflow.orders.domain.OrderStatus;

/** Fulfillment-facing order boundary: caller owns the enclosing transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class OrderFulfillmentAccess {
    private final OrderStore orders;
    public OrderFulfillmentAccess(OrderStore orders){this.orders=orders;}
    public View lock(UUID id){orders.lock(id);return orders.get(id);}
    public View get(UUID id){return orders.get(id);}
    public void transition(UUID id,OrderStatus status){orders.transition(id,status);}
}

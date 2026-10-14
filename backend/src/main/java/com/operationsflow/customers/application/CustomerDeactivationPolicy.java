package com.operationsflow.customers.application;

import java.util.UUID;

/** Orders implements this port without exposing its persistence to customers. */
public interface CustomerDeactivationPolicy {
    void requireNoOpenOrders(UUID customerId);
}

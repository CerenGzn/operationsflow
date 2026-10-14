package com.operationsflow.orders.domain;

import com.operationsflow.shared.domain.BusinessException;

public enum OrderStatus {
    RESERVED, CANCELLED, PROCESSING, READY_TO_SHIP, PARTIALLY_SHIPPED, SHIPPED;
    public boolean cancel() {
        if (this == CANCELLED) return false;
        if (this != RESERVED && this != PROCESSING && this != READY_TO_SHIP) throw new BusinessException("INVALID_TRANSITION", "Only unshipped orders can be cancelled.");
        return true;
    }
}

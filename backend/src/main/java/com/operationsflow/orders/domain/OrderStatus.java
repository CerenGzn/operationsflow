package com.operationsflow.orders.domain;

import com.operationsflow.shared.domain.BusinessException;

public enum OrderStatus {
    RESERVED, CANCELLED, PROCESSING, PARTIALLY_SHIPPED, SHIPPED;
    public boolean cancel() {
        if (this == CANCELLED) return false;
        if (this != RESERVED) throw new BusinessException("INVALID_TRANSITION", "Only reserved orders can be cancelled.");
        return true;
    }
}

package com.operationsflow.inventory.domain;

import com.operationsflow.shared.domain.BusinessException;

public enum ReservationState {
    ACTIVE, RELEASED, CONSUMED;
    /** Returns false for a retry of the same completed transition. */
    public boolean transitionTo(ReservationState target) {
        if (target == ACTIVE) throw new IllegalArgumentException("Cannot reopen reservations");
        if (this == target) return false;
        if (this != ACTIVE) throw new BusinessException("INVALID_TRANSITION", "A released reservation cannot be consumed, and a consumed reservation cannot be released.");
        return true;
    }
}

package com.operationsflow.fulfillment.domain;
public enum TaskState {
    PENDING, PICKING, PICKED, PACKED, READY_TO_SHIP, DISPATCHED, CANCELLED;
    public boolean pickingAllowed(){return this==PENDING || this==PICKING || this==PICKED;}
}

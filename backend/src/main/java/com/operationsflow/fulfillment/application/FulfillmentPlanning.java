package com.operationsflow.fulfillment.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.fulfillment.persistence.FulfillmentStore;
import com.operationsflow.audit.application.AuditService;

/** Called only inside order creation/cancellation, after the order transaction has established ownership. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class FulfillmentPlanning {
    private final FulfillmentStore store;private final AuditService audit;
    public FulfillmentPlanning(FulfillmentStore store,AuditService audit){this.store=store;this.audit=audit;}
    public void plan(UUID order){store.plan(order);audit.record("FULFILLMENT_PLANNED",order);}
    public void cancel(UUID order){for(var task:store.tasks(order)){store.cancel(task.id());audit.record("FULFILLMENT_CANCELLED",task.id());if(task.shipment()!=null)audit.record("SHIPMENT_CANCELLED",task.shipment().id());}}
}

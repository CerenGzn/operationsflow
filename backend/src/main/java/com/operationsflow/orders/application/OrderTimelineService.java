package com.operationsflow.orders.application;

import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.operationsflow.orders.api.OrderTimeline;
import com.operationsflow.orders.api.OrderTimeline.Milestone;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.orders.persistence.OrderTimelineStore;
import com.operationsflow.orders.persistence.OrderTimelineStore.Facts;
import static com.operationsflow.orders.api.OrderTimeline.Progress.*;

@Service
@Transactional(readOnly = true)
public class OrderTimelineService {
    private final OrderTimelineStore store;
    public OrderTimelineService(OrderTimelineStore store) { this.store = store; }
    public OrderTimeline get(UUID id) { return present(store.get(id)); }

    static OrderTimeline present(Facts f) {
        boolean cancelled = f.status() == OrderStatus.CANCELLED;
        var waiting = cancelled ? NOT_REACHED : PENDING;
        var stages = new ArrayList<Milestone>();
        stages.add(new Milestone("CONFIRMED","Order Confirmed",COMPLETED,f.createdAt(),"Order created and stock reserved atomically."));
        stages.add(new Milestone("PREPARING","Preparing Order",f.pickingAt() == null ? waiting : COMPLETED,
            f.pickingAt(),"First recorded picking activity; quantities may subsequently be corrected."));
        boolean packed = f.tasks() > 0 && f.packages() == f.tasks();
        stages.add(new Milestone("PACKED","Packed",packed ? COMPLETED : cancelled ? NOT_REACHED : f.packages() > 0 ? IN_PROGRESS : PENDING,
            packed ? f.packedAt() : null,f.packages()+" of "+f.tasks()+" warehouse allocations packed."));
        // No shipment-preparation milestone is claimed until an actual shipment row exists.
        if (f.shipments() > 0) {
            boolean prepared = f.shipments() == f.tasks();
            stages.add(new Milestone("SHIPMENT_PREPARED","Shipment Prepared",prepared ? COMPLETED : cancelled ? NOT_REACHED : IN_PROGRESS,
                prepared ? f.preparedAt() : null,f.shipments()+" of "+f.tasks()+" shipments prepared. Preparation is not dispatch."));
        }
        boolean shipped = f.status() == OrderStatus.SHIPPED && f.tasks() > 0 && f.dispatched() == f.tasks();
        stages.add(new Milestone("SHIPPED","Shipped",shipped ? COMPLETED : waiting,shipped ? f.dispatchedAt() : null,
            "Whole-order dispatch recorded by an operator; no external carrier or delivery confirmation."));
        if (cancelled) stages.add(new Milestone("CANCELLED","Cancelled",COMPLETED,f.cancelledAt(),"Order cancelled and reserved stock released. No dispatch."));
        return new OrderTimeline(f.id(),f.status(),java.util.List.copyOf(stages));
    }
}

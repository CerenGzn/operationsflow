package com.operationsflow.fulfillment.application;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.fulfillment.persistence.FulfillmentStore;
import com.operationsflow.orders.application.OrderFulfillmentAccess;
import com.operationsflow.orders.domain.OrderStatus;
import com.operationsflow.inventory.application.InventoryService;
import com.operationsflow.inventory.api.InventoryContracts.Terminal;
import com.operationsflow.audit.application.AuditService;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Validated
@Transactional
public class FulfillmentService {
    private final FulfillmentStore store;private final OrderFulfillmentAccess orders;private final InventoryService inventory;private final AuditService audit;
    public FulfillmentService(FulfillmentStore store,OrderFulfillmentAccess orders,InventoryService inventory,AuditService audit){this.store=store;this.orders=orders;this.inventory=inventory;this.audit=audit;}
    private TaskView lock(UUID id){UUID order=store.orderId(id);var current=orders.lock(order);if(current.status()==OrderStatus.CANCELLED)throw conflict("Cancelled orders cannot be fulfilled.");return store.task(id);}
    private void version(TaskView task,long expected){if(task.version()!=expected)throw new BusinessException("STALE_VERSION","This task changed. Refresh before continuing.");}
    private void started(UUID order){if(orders.get(order).status()==OrderStatus.RESERVED)orders.transition(order,OrderStatus.PROCESSING);}
    public TaskView pick(UUID id,UUID sku,@NotNull @Valid Pick request){
        var task=lock(id);
        var line=task.lines().stream().filter(l->l.skuId().equals(sku)).findFirst().orElseThrow(()->new BusinessException("NOT_FOUND","SKU is not allocated to this task."));
        if(request.quantity()>line.quantity())throw new BusinessException("INVALID_REQUEST","Picked quantity cannot exceed the allocation.");
        if(request.quantity()==line.pickedQuantity())return task;
        if(!task.state().pickingAllowed())throw conflict("Picking cannot change after packing.");
        version(task,request.version());store.pick(id,sku,request.quantity());
        var lines=store.task(id).lines();TaskState state=lines.stream().allMatch(l->l.quantity()==l.pickedQuantity())?TaskState.PICKED:lines.stream().anyMatch(l->l.pickedQuantity()>0)?TaskState.PICKING:TaskState.PENDING;
        store.state(id,state);started(task.orderId());audit.record("PICK_CONFIRMED",id,"sku="+sku+";picked="+line.pickedQuantity()+"->"+request.quantity());return store.task(id);
    }
    public TaskView pack(UUID id,@NotNull @Valid Pack request){
        var task=lock(id);var quantities=new HashMap<UUID,Long>();
        for(var line:request.lines())if(quantities.put(line.skuId(),line.quantity())!=null)throw new BusinessException("INVALID_REQUEST","Each SKU must be verified exactly once.");
        if(quantities.size()!=task.lines().size() || task.lines().stream().anyMatch(l->!Objects.equals(quantities.get(l.skuId()),l.quantity())))throw new BusinessException("INVALID_REQUEST","Verify the full allocated quantity of every SKU.");
        if(task.packageRecord()!=null)return task;
        version(task,request.version());if(task.state()!=TaskState.PICKED)throw conflict("Every SKU must be picked in full before packing.");
        UUID pack=store.pack(id);store.state(id,TaskState.PACKED);audit.record("PACKAGE_PACKED",pack);return store.task(id);
    }
    public TaskView shipment(UUID id,@NotNull @Valid PrepareShipment request){
        var task=lock(id);
        if(task.shipment()!=null){if(!task.shipment().carrier().equals(request.carrier()) || !task.shipment().trackingReference().equals(request.trackingReference()))throw new BusinessException("IDEMPOTENCY_CONFLICT","A shipment already exists with different carrier/tracking details.");return task;}
        version(task,request.version());if(task.state()!=TaskState.PACKED)throw conflict("Pack and verify this allocation before preparing its shipment.");
        UUID shipment=store.shipment(id,task.packageRecord().id(),request.carrier(),request.trackingReference());store.state(id,TaskState.READY_TO_SHIP);audit.record("SHIPMENT_PREPARED",shipment);
        if(store.tasks(task.orderId()).stream().allMatch(t->t.state()==TaskState.READY_TO_SHIP))orders.transition(task.orderId(),OrderStatus.READY_TO_SHIP);
        return store.task(id);
    }
    public OrderView dispatch(UUID id){
        var order=orders.lock(id);var tasks=store.tasks(id);
        if(order.status()==OrderStatus.SHIPPED)return new OrderView(id,order.orderNumber(),order.status(),order.customer(),tasks);
        if(order.status()!=OrderStatus.READY_TO_SHIP || tasks.isEmpty() || tasks.stream().anyMatch(t->t.state()!=TaskState.READY_TO_SHIP))throw conflict("All warehouse allocations must have verified packages and prepared shipments before whole-order dispatch.");
        inventory.consumeForOrder(id,order.reservationId(),new Terminal("Whole-order dispatch "+order.orderNumber()));
        for(var task:tasks){store.ship(task.id());audit.record("SHIPMENT_DISPATCHED",task.shipment().id());}
        orders.transition(id,OrderStatus.SHIPPED);audit.record("ORDER_DISPATCHED",id);
        return new OrderView(id,order.orderNumber(),OrderStatus.SHIPPED,order.customer(),store.tasks(id));
    }
    private BusinessException conflict(String message){return new BusinessException("INVALID_TRANSITION",message);}
}

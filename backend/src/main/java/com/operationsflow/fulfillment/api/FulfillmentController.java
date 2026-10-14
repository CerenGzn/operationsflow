package com.operationsflow.fulfillment.api;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.fulfillment.api.FulfillmentContracts.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.fulfillment.application.*;
import com.operationsflow.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/fulfillment")
@Tag(name="Fulfillment",description="Authenticated reads for all roles; ADMIN/WAREHOUSE_OPERATOR writes with CSRF. Independent warehouse preparation, whole-order dispatch only. No stock deductions during picking or packing.")
public class FulfillmentController {
    private final FulfillmentService service;private final FulfillmentQueries queries;
    public FulfillmentController(FulfillmentService service,FulfillmentQueries queries){this.service=service;this.queries=queries;}
    @GetMapping("/tasks") @Operation(summary="Paged warehouse queue",description="Optional warehouseId/state/order-number query. Page zero-based, size 1–100; oldest tasks first.")
    public PageResponse<TaskView> tasks(@RequestParam(required=false)UUID warehouseId,@RequestParam(required=false)TaskState state,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return queries.tasks(warehouseId,state,query,page,size);}
    @GetMapping("/orders/{id}") public OrderView order(@PathVariable UUID id){return queries.order(id);}
    @PutMapping("/tasks/{id}/picks/{skuId}") @Operation(summary="Confirm absolute picked quantity",description="Version required. Quantity 0..allocated. Corrections allowed before packing. Same quantity is a no-op; stale changed quantities return 409.")
    public TaskView pick(@PathVariable UUID id,@PathVariable UUID skuId,@Valid @RequestBody Pick body){return service.pick(id,skuId,body);}
    @PostMapping("/tasks/{id}/pack") @Operation(summary="Verify contents and create one allocation package",description="Version and every SKU with its exact allocated quantity required. Every item must already be picked. Identical retries return the same package.")
    public TaskView pack(@PathVariable UUID id,@Valid @RequestBody Pack body){return service.pack(id,body);}
    @PostMapping("/tasks/{id}/shipment") @Operation(summary="Prepare carrier/tracking details",description="Requires a verified package and version. Same details replay; different details conflict. Does not dispatch or consume inventory.")
    public TaskView shipment(@PathVariable UUID id,@Valid @RequestBody PrepareShipment body){return service.shipment(id,body);}
    @PostMapping("/orders/{id}/dispatch") @Operation(summary="Dispatch the whole order exactly once",description="All warehouses must be READY_TO_SHIP. Atomically consumes the entire reservation and marks all shipments dispatched. Repeated dispatch returns current state. Cancellation competes on the same order lock.")
    public OrderView dispatch(@PathVariable UUID id){return service.dispatch(id);}
    @GetMapping("/shipments") @Operation(summary="Paged shipment history",description="Optional warehouseId and state READY/DISPATCHED/CANCELLED. Newest first; page zero-based, size 1–100.")
    public PageResponse<ShipmentView> shipments(@RequestParam(required=false)UUID warehouseId,@RequestParam(required=false)String state,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return queries.shipments(warehouseId,state,page,size);}
}

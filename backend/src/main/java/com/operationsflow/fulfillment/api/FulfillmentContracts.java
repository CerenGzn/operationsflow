package com.operationsflow.fulfillment.api;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.operationsflow.fulfillment.domain.TaskState;
import com.operationsflow.orders.domain.OrderStatus;

public final class FulfillmentContracts {
    private FulfillmentContracts() {}
    public record Pick(@NotNull @Min(0) @Max(1000000000) Long quantity,@NotNull @PositiveOrZero Long version) {}
    public record VerifiedLine(@NotNull UUID skuId,@Min(1) @Max(1000000000) long quantity) {}
    public record Pack(@NotNull @PositiveOrZero Long version,@NotEmpty @Size(max=50) List<@NotNull @Valid VerifiedLine> lines) {}
    public record PrepareShipment(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=100) String carrier,@NotBlank @Size(max=160) String trackingReference) {
        public PrepareShipment { carrier=carrier==null?null:carrier.strip();trackingReference=trackingReference==null?null:trackingReference.strip(); }
    }
    public record LineView(UUID skuId,String skuCode,String productName,long quantity,long pickedQuantity) {}
    public record PackageView(UUID id,Instant packedAt) {}
    public record ShipmentView(UUID id,UUID taskId,UUID orderId,String orderNumber,UUID warehouseId,String warehouseCode,UUID packageId,String carrier,String trackingReference,String state,Instant createdAt,Instant dispatchedAt) {}
    public record TaskView(UUID id,UUID orderId,String orderNumber,UUID warehouseId,String warehouseCode,TaskState state,long version,List<LineView> lines,PackageView packageRecord,ShipmentView shipment) {}
    public record OrderView(UUID orderId,String orderNumber,OrderStatus status,com.operationsflow.orders.api.OrderContracts.CustomerSnapshot destination,List<TaskView> tasks) {}
}

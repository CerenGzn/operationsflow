package com.operationsflow.reporting.domain;
import java.util.*;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.BusinessException;
public record ReportFilter(DateWindow range,UUID warehouseId,UUID skuId,String status,String query,int threshold) {
    public ReportFilter { status=status==null?"":status.strip();query=ListQuery.search(query).toLowerCase(Locale.ROOT);DateWindow.threshold(threshold); }
    public void validate(ReportKind kind){
        var allowed=switch(kind){
            case ORDERS -> Set.of("RESERVED","PROCESSING","READY_TO_SHIP","SHIPPED","CANCELLED");
            case MOVEMENTS -> Set.of("RECEIPT","ADJUSTMENT","RESERVATION","RELEASE","FULFILLMENT");
            case FULFILLMENT -> Set.of("PENDING","PICKING","PICKED","PACKED","READY_TO_SHIP","DISPATCHED","CANCELLED");
            case SHIPMENTS -> Set.of("READY","DISPATCHED","CANCELLED");
            default -> Set.<String>of();
        };
        if(kind==ReportKind.AUDIT){if(warehouseId!=null||skuId!=null||(!status.isEmpty()&&!status.matches("[A-Z_]{1,64}")))throw new BusinessException("INVALID_QUERY","Audit supports action and query filters, not warehouse/SKU filters.");}
        else if(!status.isEmpty()&&!allowed.contains(status))throw new BusinessException("INVALID_QUERY","Unsupported status/type for this report.");
    }
}

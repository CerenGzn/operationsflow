package com.operationsflow.reporting.api;
import java.time.Instant;
import java.util.*;
import com.operationsflow.reporting.domain.DateWindow;
public final class ReportingContracts {
    private ReportingContracts(){}
    public record Point(String label,String value,String secondary){}
    public record Activity(String type,String reference,Instant occurredAt){}
    public record Dashboard(Instant asOf,DateWindow range,int lowStockThreshold,Map<String,String> kpis,List<Point> orderVolume,List<Point> orderStatus,List<Point> inventoryByWarehouse,List<Point> movementTrends,List<Point> shipmentThroughput,List<Point> fulfillmentStatus,List<Point> topSkus,List<Activity> recentActivity){}
    public record Column(String key,String label,boolean numeric){}
    public record ReportPage(String kind,String dateBasis,Instant asOf,DateWindow range,List<Column> columns,List<Map<String,String>> content,int page,int size,long totalElements,int totalPages){}
}

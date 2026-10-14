package com.operationsflow.reporting.api;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.reporting.api.ReportingContracts.*;
import com.operationsflow.reporting.application.ReportingService;
import com.operationsflow.reporting.domain.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name="Analytics and reports",description="Read-only, permission-scoped PostgreSQL snapshots. Inclusive calendar date ranges in Europe/Berlin or UTC; maximum 366 days. No revenue metrics for unpriced orders.")
public class ReportingController {
    private final ReportingService service;
    public ReportingController(ReportingService service){this.service=service;}
    @GetMapping("/analytics/dashboard") @Operation(summary="Current KPIs and date-filtered operational charts",description="KPIs and warehouse inventory are current/all-time snapshots, not date-filtered. Movement trends are absent for SALES_OPERATOR. Threshold is inclusive available-stock maximum for established active SKU/warehouse balances.")
    public Dashboard dashboard(@RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,@RequestParam(defaultValue="Europe/Berlin")String zone,@RequestParam(defaultValue="10")int threshold){return service.dashboard(DateWindow.of(from,to,zone),threshold);}
    @GetMapping("/reports/{kind}") @Operation(summary="Filter and page a report",description="Kinds: orders, inventory, low-stock, movements, fulfillment, shipments, audit. Audit ADMIN only; movements ADMIN/WAREHOUSE_OPERATOR. Page 0+, size 1–100. Inventory/low-stock are current snapshots; dates do not apply. Other reports filter creation/event dates, with current states. Free-text sensitive fields are excluded.")
    public ReportPage report(@PathVariable String kind,@RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,@RequestParam(defaultValue="Europe/Berlin")String zone,@RequestParam(required=false)UUID warehouseId,@RequestParam(required=false)UUID skuId,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="10")int threshold,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.report(ReportKind.parse(kind),filter(from,to,zone,warehouseId,skuId,status,query,threshold),page,size);}
    @GetMapping("/reports/{kind}/export") @Operation(summary="Export the full filtered report to safe CSV",description="Same role checks and filters as reports, independent of the visible page. Maximum 5,000 rows; larger matches return structured 422 without truncation. Formula-significant text is neutralized. UTC timestamps. No customer PII, passwords or raw audit details.")
    public ResponseEntity<String> export(@PathVariable String kind,@RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,@RequestParam(defaultValue="Europe/Berlin")String zone,@RequestParam(required=false)UUID warehouseId,@RequestParam(required=false)UUID skuId,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="10")int threshold){
        var report=ReportKind.parse(kind);String csv=service.export(report,filter(from,to,zone,warehouseId,skuId,status,query,threshold));
        return ResponseEntity.ok().contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"operationsflow-"+report.path+".csv\"").cacheControl(CacheControl.noStore()).body(csv);
    }
    private ReportFilter filter(LocalDate from,LocalDate to,String zone,UUID warehouse,UUID sku,String status,String query,int threshold){return new ReportFilter(DateWindow.of(from,to,zone),warehouse,sku,status,query,threshold);}
}

package com.operationsflow.reporting.application;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.reporting.api.ReportingContracts.*;
import com.operationsflow.reporting.domain.*;
import com.operationsflow.reporting.persistence.*;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportingService {
    public static final int EXPORT_LIMIT=5000;
    private final AnalyticsStore analytics;private final ReportStore reports;
    public ReportingService(AnalyticsStore analytics,ReportStore reports){this.analytics=analytics;this.reports=reports;}
    public Dashboard dashboard(DateWindow range,int threshold){
        ReportingAccess.require(ReportKind.ORDERS);DateWindow.threshold(threshold);analytics.limit();var p=AnalyticsStore.parameters(range);
        return new Dashboard(analytics.asOf(),range,threshold,analytics.kpis(threshold),analytics.orderVolume(p),analytics.orderStatus(p),analytics.inventoryByWarehouse(),ReportingAccess.movements()?analytics.movements(p):null,analytics.throughput(p),analytics.fulfillmentStatus(p),analytics.topSkus(p),analytics.activity(p));
    }
    public ReportPage report(ReportKind kind,ReportFilter filter,int page,int size){
        ReportingAccess.require(kind);ListQuery.page(page,size,"id","asc",Set.of("id"));analytics.limit();var spec=reports.spec(kind,filter);long total=reports.count(spec);
        return new ReportPage(kind.path,spec.dateBasis(),analytics.asOf(),filter.range(),spec.columns(),reports.rows(spec,size,(long)page*size),page,size,total,(int)Math.min(Integer.MAX_VALUE,(total+size-1)/size));
    }
    public String export(ReportKind kind,ReportFilter filter){
        ReportingAccess.require(kind);analytics.limit();var spec=reports.spec(kind,filter);var rows=reports.rows(spec,EXPORT_LIMIT+1,0);
        if(rows.size()>EXPORT_LIMIT)throw new BusinessException("EXPORT_TOO_LARGE","More than 5,000 rows match. Narrow the filters before exporting; no partial export was generated.");
        return CsvWriter.write(spec.columns(),rows);
    }
}

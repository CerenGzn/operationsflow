package com.operationsflow.reporting.application;
import org.springframework.security.access.AccessDeniedException;
import com.operationsflow.security.application.CurrentActor;
import com.operationsflow.security.domain.Role;
import com.operationsflow.reporting.domain.ReportKind;
public final class ReportingAccess {
    private ReportingAccess(){}
    public static boolean movements(){return CurrentActor.get().role()!=Role.SALES_OPERATOR;}
    public static void requireExport(ReportKind kind){
        if(CurrentActor.get().role()==Role.DEMO_VIEWER)
            throw new AccessDeniedException("Exports are not permitted for demo viewers");
        require(kind);
    }
    public static void require(ReportKind kind){
        var role=CurrentActor.get().role();
        if(role==Role.DEMO_VIEWER && !java.util.Set.of(ReportKind.ORDERS,ReportKind.INVENTORY,
            ReportKind.LOW_STOCK,ReportKind.MOVEMENTS,ReportKind.FULFILLMENT).contains(kind))
            throw new AccessDeniedException("Report not permitted for demo viewers");
        if((kind==ReportKind.AUDIT && role!=Role.ADMIN)||(kind==ReportKind.MOVEMENTS && role==Role.SALES_OPERATOR))throw new AccessDeniedException("Report not permitted for this role");
    }
}

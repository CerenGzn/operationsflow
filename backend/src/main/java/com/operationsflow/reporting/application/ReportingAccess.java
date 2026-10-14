package com.operationsflow.reporting.application;
import org.springframework.security.access.AccessDeniedException;
import com.operationsflow.security.application.CurrentActor;
import com.operationsflow.security.domain.Role;
import com.operationsflow.reporting.domain.ReportKind;
public final class ReportingAccess {
    private ReportingAccess(){}
    public static boolean movements(){return CurrentActor.get().role()!=Role.SALES_OPERATOR;}
    public static void require(ReportKind kind){
        var role=CurrentActor.get().role();
        if((kind==ReportKind.AUDIT && role!=Role.ADMIN)||(kind==ReportKind.MOVEMENTS && role==Role.SALES_OPERATOR))throw new AccessDeniedException("Report not permitted for this role");
    }
}

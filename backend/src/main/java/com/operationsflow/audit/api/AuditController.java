package com.operationsflow.audit.api;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.audit.application.AuditQueries;
import com.operationsflow.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name="Audit",description="Administrator-only immutable event history. No credential, token, session ID, password or request-body capture.")
public class AuditController {
    private final AuditQueries queries;
    public AuditController(AuditQueries queries){this.queries=queries;}
    @GetMapping public PageResponse<AuditQueries.Event> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return queries.list(page,size);}
}

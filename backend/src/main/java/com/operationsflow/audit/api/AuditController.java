package com.operationsflow.audit.api;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.api.PageResponse;
@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name="Audit",description="Administrator-only immutable event history. No credential, token, session ID, password or request-body capture.")
@Transactional(readOnly=true)
public class AuditController {
    private final JdbcTemplate jdbc;public AuditController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Event(UUID id,UUID actorId,String actorKind,String action,UUID targetId,String detail,Instant createdAt){}
    @GetMapping public PageResponse<Event> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){
        ListQuery.page(page,size,"id","asc",Set.of("id"));long total=jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class);
        var rows=jdbc.query("select * from operationsflow.audit_event order by created_at desc,id desc limit ? offset ?",(r,n)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("actor_kind"),r.getString("action"),r.getObject("target_id",UUID.class),r.getString("detail"),r.getTimestamp("created_at").toInstant()),size,(long)page*size);
        return new PageResponse<>(rows,page,size,total,(int)Math.ceil((double)total/size));
    }
}

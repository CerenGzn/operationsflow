package com.operationsflow.audit.application;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.api.PageResponse;

@Service
@Transactional(readOnly=true)
public class AuditQueries {
    private final JdbcTemplate jdbc;public AuditQueries(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Event(UUID id,UUID actorId,String actorKind,String action,UUID targetId,String detail,Instant createdAt){}
    public PageResponse<Event> list(int page,int size){
        ListQuery.page(page,size,"id","asc",Set.of("id"));long total=jdbc.queryForObject("select count(*) from operationsflow.audit_event",Long.class);
        var rows=jdbc.query("select * from operationsflow.audit_event order by created_at desc,id desc limit ? offset ?",(r,n)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("actor_kind"),r.getString("action"),r.getObject("target_id",UUID.class),r.getString("detail"),r.getTimestamp("created_at").toInstant()),size,(long)page*size);
        return new PageResponse<>(rows,page,size,total,(int)Math.ceil((double)total/size));
    }
}

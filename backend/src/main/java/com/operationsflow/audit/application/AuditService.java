package com.operationsflow.audit.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.operationsflow.security.application.CurrentActor;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(String action,UUID target) { insert(CurrentActor.id(),"USER",action,target,""); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(String action,UUID target,String detail) { insert(CurrentActor.id(),"USER",action,target,detail); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void userChange(String action,UUID target,String detail) { insert(CurrentActor.id(),"USER",action,target,detail); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bootstrap(UUID target) { insert(null,"SYSTEM","ADMIN_BOOTSTRAPPED",target,""); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void authentication(String action,UUID actor) { insert(actor,actor == null ? "ANONYMOUS" : "USER",action,null,""); }
    private void insert(UUID actor,String kind,String action,UUID target,String detail) {
        jdbc.update("insert into operationsflow.audit_event(id,actor_id,actor_kind,action,target_id,detail) values (?,?,?,?,?,?)",UUID.randomUUID(),actor,kind,action,target,detail);
    }
}

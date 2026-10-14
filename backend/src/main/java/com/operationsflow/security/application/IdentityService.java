package com.operationsflow.security.application;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import com.operationsflow.security.persistence.IdentityStore;
import com.operationsflow.security.domain.Role;
import com.operationsflow.security.api.IdentityContracts.*;
import com.operationsflow.audit.application.AuditService;
import com.operationsflow.shared.application.ListQuery;
import com.operationsflow.shared.api.PageResponse;
import com.operationsflow.shared.domain.BusinessException;

@Service
@Transactional(readOnly=true)
public class IdentityService {
    private final IdentityStore users; private final PasswordEncoder encoder; private final AuditService audit;
    public IdentityService(IdentityStore users,PasswordEncoder encoder,AuditService audit){this.users=users;this.encoder=encoder;this.audit=audit;}
    public static String email(String value){return value.strip().toLowerCase(Locale.ROOT);}
    public static void passwordPolicy(String value){
        if(value==null || value.length()<12 || value.getBytes(StandardCharsets.UTF_8).length>72 || value.isBlank()) throw new BusinessException("INVALID_REQUEST","Passwords require at least 12 characters and at most 72 UTF-8 bytes.");
    }
    private void admin(){
        var actor=CurrentActor.get();var current=users.byId(actor.id()).orElseThrow(() -> new AccessDeniedException("Administrator required"));
        if(!current.active() || current.role()!=Role.ADMIN || current.mustChangePassword() || current.securityVersion()!=actor.securityVersion())throw new AccessDeniedException("Administrator required");
    }
    public View me(){return View.from(users.byId(CurrentActor.id()).orElseThrow(() -> new AccessDeniedException("Session invalid")));}
    public PageResponse<View> list(String query,int page,int size){admin();ListQuery.page(page,size,"id","asc",Set.of("id"));String term=ListQuery.search(query).toLowerCase(Locale.ROOT);long total=users.total(term);return new PageResponse<>(users.list(term,page,size).stream().map(View::from).toList(),page,size,total,(int)Math.ceil((double)total/size));}
    @Transactional public View create(Create r){users.guard();admin();passwordPolicy(r.password());UUID id=users.create(email(r.email()),r.displayName().strip(),encoder.encode(r.password()),r.role(),true);audit.userChange("USER_CREATED",id,"role="+r.role());return View.from(users.byId(id).orElseThrow());}
    @Transactional public View update(UUID id,Update r){
        users.guard();admin();var old=users.lock(id);version(old.version(),r.version());
        if(old.active() && old.role()==Role.ADMIN && (!r.active() || r.role()!=Role.ADMIN) && users.activeAdmins()<=1)throw new BusinessException("FINAL_ADMIN","The final active administrator cannot be disabled or demoted.");
        users.update(id,email(r.email()),r.displayName().strip(),r.role(),r.active());audit.userChange("USER_UPDATED",id,"role="+old.role()+"->"+r.role()+";active="+old.active()+"->"+r.active());return View.from(users.byId(id).orElseThrow());
    }
    @Transactional public void reset(UUID id,Reset r){users.guard();admin();var old=users.lock(id);version(old.version(),r.version());passwordPolicy(r.password());users.password(id,encoder.encode(r.password()),true);audit.record("PASSWORD_RESET",id);}
    @Transactional public void change(Change r){
        var actor=CurrentActor.get();var old=users.lock(actor.id());
        if(!old.active() || old.securityVersion()!=actor.securityVersion())throw new AccessDeniedException("Session invalid");
        if(r.currentPassword().getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(r.currentPassword(),old.passwordHash()))throw new BusinessException("INVALID_REQUEST","Current password is incorrect.");
        passwordPolicy(r.newPassword());if(encoder.matches(r.newPassword(),old.passwordHash()))throw new BusinessException("INVALID_REQUEST","Choose a different password.");
        users.password(old.id(),encoder.encode(r.newPassword()),false);audit.record("PASSWORD_CHANGED",old.id());
    }
    @Transactional public void bootstrap(String email,String password){
        users.guard();if(users.count()!=0)throw new IllegalStateException("Bootstrap requires an empty user table; remove bootstrap environment variables after first use.");
        passwordPolicy(password);if(email==null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length()>254)throw new IllegalStateException("Valid bootstrap email required");
        UUID id=users.create(email(email),"Initial administrator",encoder.encode(password),Role.ADMIN,false);audit.bootstrap(id);
    }
    private void version(long current,long expected){if(current!=expected)throw new BusinessException("STALE_VERSION","User changed. Reload before saving.");}
}

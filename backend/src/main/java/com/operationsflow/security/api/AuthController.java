package com.operationsflow.security.api;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.operationsflow.security.api.IdentityContracts.*;
import com.operationsflow.security.application.*;
import com.operationsflow.security.domain.SessionActor;
import com.operationsflow.security.persistence.IdentityStore;
import com.operationsflow.audit.application.AuditService;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name="Session authentication",description="Cookie sessions; GET csrf first and send its header/token on every mutation, including login. Tokens rotate on login/logout. No public signup.")
public class AuthController {
    private final IdentityStore users;private final IdentityService service;private final PasswordEncoder passwords;private final LoginThrottle throttle;private final AuditService audit;
    private final HttpSessionSecurityContextRepository contexts;private final HttpSessionCsrfTokenRepository csrf;
    private final String dummyHash;
    public AuthController(IdentityStore users,IdentityService service,PasswordEncoder passwords,LoginThrottle throttle,AuditService audit,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf){
        this.users=users;this.service=service;this.passwords=passwords;this.throttle=throttle;this.audit=audit;this.contexts=contexts;this.csrf=csrf;dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @io.swagger.v3.oas.annotations.Operation(security = {})
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("headerName",token.getHeaderName(),"token",token.getToken());}
    @io.swagger.v3.oas.annotations.Operation(security = {})
    @PostMapping("/login") public ResponseEntity<?> login(@Valid @RequestBody Login body,HttpServletRequest request,HttpServletResponse response){
        String email=IdentityService.email(body.email());var admission=throttle.acquire(request.getRemoteAddr(),email);
        if(!admission.allowed()){if(admission.recordRejection())audit.authentication("LOGIN_RATE_LIMITED",null);return ResponseEntity.status(429).header("Retry-After","900").body(problem(429,"LOGIN_RATE_LIMITED","Too many login attempts. Try again later."));}
        var account=users.byEmail(email);boolean matched=body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72 && passwords.matches(body.password(),account.map(IdentityStore.Account::passwordHash).orElse(dummyHash));
        if(!matched || account.isEmpty() || !account.get().active()){audit.authentication("LOGIN_FAILED",null);return ResponseEntity.status(401).body(problem(401,"LOGIN_FAILED","Invalid email or password."));}
        var user=account.get();var actor=new SessionActor(user.id(),user.role(),user.securityVersion());
        var authentication=UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role())));
        audit.authentication("LOGIN_SUCCEEDED",user.id());
        request.getSession();request.changeSessionId();csrf.saveToken(null,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        return ResponseEntity.ok(View.from(user));
    }
    @GetMapping("/session") public View session(){return service.me();}
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response){audit.authentication("LOGOUT",CurrentActor.id());invalidate(request,response);return ResponseEntity.noContent().build();}
    @PostMapping("/change-password") public ResponseEntity<?> change(@Valid @RequestBody Change body,HttpServletRequest request,HttpServletResponse response){
        var admission=throttle.acquire(request.getRemoteAddr(),"password-change:"+CurrentActor.id());
        if(!admission.allowed()){if(admission.recordRejection())audit.authentication("PASSWORD_CHANGE_RATE_LIMITED",CurrentActor.id());return ResponseEntity.status(429).header("Retry-After","900").body(problem(429,"RATE_LIMITED","Too many attempts. Try again later."));}
        service.change(body);invalidate(request,response);return ResponseEntity.noContent().build();}
    private void invalidate(HttpServletRequest request,HttpServletResponse response){
        var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("JSESSIONID","").httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/").maxAge(0).build().toString());
    }
    private ProblemDetail problem(int status,String code,String detail){var p=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),detail);p.setProperty("code",code);return p;}
}

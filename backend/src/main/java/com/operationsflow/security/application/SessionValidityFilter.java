package com.operationsflow.security.application;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import com.operationsflow.security.domain.SessionActor;
import com.operationsflow.security.persistence.IdentityStore;

public class SessionValidityFilter extends OncePerRequestFilter {
    private final IdentityStore users;
    public SessionValidityFilter(IdentityStore users){this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof SessionActor actor){
            var current=users.byId(actor.id());
            if(current.isEmpty() || !current.get().active() || current.get().securityVersion()!=actor.securityVersion()){
                SecurityContextHolder.clearContext();var session=request.getSession(false);if(session!=null)session.invalidate();
                SecurityResponses.write(response,401,"SESSION_EXPIRED","Your session expired or was revoked. Sign in again.");return;
            }
            if(current.get().mustChangePassword() && !Set.of("/api/v1/auth/session","/api/v1/auth/csrf","/api/v1/auth/change-password","/api/v1/auth/logout").contains(request.getRequestURI().substring(request.getContextPath().length()))){
                SecurityResponses.write(response,403,"PASSWORD_CHANGE_REQUIRED","Change your password before accessing operations.");return;
            }
        }
        chain.doFilter(request,response);
    }
}

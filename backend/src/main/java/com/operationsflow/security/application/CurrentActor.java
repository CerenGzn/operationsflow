package com.operationsflow.security.application;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import com.operationsflow.security.domain.SessionActor;

public final class CurrentActor {
    private CurrentActor() {}
    public static SessionActor get() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof SessionActor actor))
            throw new AccessDeniedException("Authenticated actor required");
        return actor;
    }
    public static UUID id() { return get().id(); }
    public static String label() { return "user:"+id(); }
}

package com.operationsflow.security.application;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final IdentityService users;private final Environment environment;
    public AdminBootstrap(IdentityService users,Environment environment){this.users=users;this.environment=environment;}
    @Override public void run(ApplicationArguments args){
        String email=environment.getProperty("BOOTSTRAP_ADMIN_EMAIL"),password=environment.getProperty("BOOTSTRAP_ADMIN_PASSWORD");
        if(email==null && password==null)return;
        if(email==null || password==null)throw new IllegalStateException("Both bootstrap environment variables are required.");
        users.bootstrap(email,password);
    }
}

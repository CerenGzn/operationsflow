package com.operationsflow.security.application;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.operationsflow.security.persistence.IdentityStore;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean io.swagger.v3.oas.models.OpenAPI securityDocumentation(){
        return new io.swagger.v3.oas.models.OpenAPI().components(new io.swagger.v3.oas.models.Components()
            .addSecuritySchemes("sessionCookie",new io.swagger.v3.oas.models.security.SecurityScheme().type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY).in(io.swagger.v3.oas.models.security.SecurityScheme.In.COOKIE).name("JSESSIONID").description("Login through /api/v1/auth/login. Browser manages this HttpOnly cookie.")))
            .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("sessionCookie"));
    }
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean HttpSessionCsrfTokenRepository csrfRepository(){return new HttpSessionCsrfTokenRepository();}
    @Bean HttpSessionSecurityContextRepository securityContextRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean SecurityFilterChain security(HttpSecurity http,IdentityStore users,HttpSessionCsrfTokenRepository csrf,HttpSessionSecurityContextRepository contexts)throws Exception{
        http.httpBasic(b->b.disable()).formLogin(f->f.disable()).logout(l->l.disable()).requestCache(c->c.disable());
        http.securityContext(c->c.securityContextRepository(contexts).requireExplicitSave(true));
        http.sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED));
        http.csrf(c->c.csrfTokenRepository(csrf));
        http.exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->SecurityResponses.write(res,401,"AUTHENTICATION_REQUIRED","Sign in to continue."))
            .accessDeniedHandler((req,res,ex)->SecurityResponses.write(res,403,ex instanceof org.springframework.security.web.csrf.CsrfException?"CSRF_INVALID":"FORBIDDEN",ex instanceof org.springframework.security.web.csrf.CsrfException?"Refresh your session and retry with a valid CSRF token.":"Your role does not permit this action.")));
        http.authorizeHttpRequests(a->a
            .requestMatchers(HttpMethod.GET,"/api/v1/auth/csrf","/actuator/health","/actuator/health/liveness","/actuator/health/readiness","/actuator/health/demo").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/v1/auth/login").permitAll()
            .requestMatchers("/api/v1/auth/session","/api/v1/auth/logout","/api/v1/auth/change-password").authenticated()
            .requestMatchers("/api/v1/users/**","/api/v1/audit-events/**","/api/v1/messaging/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").hasRole("ADMIN")
            // Explicit viewer allowlist: no raw orders, destinations, tracking, ledger actors or exports.
            // Existing role matchers below retain their original privileges.
            .requestMatchers(HttpMethod.GET,"/api/v1/system","/api/v1/products","/api/v1/products/{id}",
                "/api/v1/warehouses","/api/v1/warehouses/{id}","/api/v1/inventory/balances","/api/v1/inventory/availability",
                "/api/v1/analytics/dashboard","/api/v1/reports/orders","/api/v1/reports/inventory",
                "/api/v1/reports/low-stock","/api/v1/reports/fulfillment")
                .hasAnyRole("ADMIN","WAREHOUSE_OPERATOR","SALES_OPERATOR","DEMO_VIEWER")
            .requestMatchers(HttpMethod.GET,"/api/v1/reports/movements")
                .hasAnyRole("ADMIN","WAREHOUSE_OPERATOR","DEMO_VIEWER")
            .requestMatchers(HttpMethod.GET,"/api/v1/system","/api/v1/products/**","/api/v1/warehouses/**","/api/v1/orders/**","/api/v1/inventory/balances","/api/v1/inventory/availability").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR","SALES_OPERATOR")
            .requestMatchers("/api/v1/products/**","/api/v1/warehouses/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/customers/**","/api/v1/orders/**").hasAnyRole("ADMIN","SALES_OPERATOR")
            .requestMatchers(HttpMethod.GET,"/api/v1/fulfillment/**").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR","SALES_OPERATOR")
            .requestMatchers("/api/v1/fulfillment/**").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR")
            .requestMatchers("/api/v1/inventory/**").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR")
            .requestMatchers(HttpMethod.GET,"/api/v1/reports/audit","/api/v1/reports/audit/export").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET,"/api/v1/reports/movements","/api/v1/reports/movements/export").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR")
            .requestMatchers(HttpMethod.GET,"/api/v1/analytics/dashboard","/api/v1/reports/**").hasAnyRole("ADMIN","WAREHOUSE_OPERATOR","SALES_OPERATOR")
            .anyRequest().denyAll());
        http.addFilterBefore(new SessionValidityFilter(users),AuthorizationFilter.class);
        http.headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'"))
            .referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}

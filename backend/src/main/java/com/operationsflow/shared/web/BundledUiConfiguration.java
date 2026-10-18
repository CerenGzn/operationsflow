package com.operationsflow.shared.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Public HTML/assets only; the existing operational API security chain is unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "operationsflow.ui.enabled", havingValue = "true")
public class BundledUiConfiguration {
    public static final String[] DOCUMENTS = {"/", "/index.html", "/dashboard", "/products",
        "/warehouses", "/inventory", "/customers", "/orders", "/users", "/fulfillment", "/reports"};

    @Bean
    @Order(1)
    SecurityFilterChain bundledUiSecurity(HttpSecurity http) throws Exception {
        String[] paths = java.util.Arrays.copyOf(DOCUMENTS, DOCUMENTS.length + 1);
        paths[DOCUMENTS.length] = "/assets/**";
        http.securityMatcher(paths)
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, paths).permitAll()
                .requestMatchers(HttpMethod.HEAD, paths).permitAll().anyRequest().denyAll())
            .requestCache(cache -> cache.disable())
            .securityContext(context -> context.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'"))
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        // No CSRF exclusions, API matchers, or operational-role changes.
        return http.build();
    }
}

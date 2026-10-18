package com.operationsflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.servlet.session.cookie.secure=true",
    "management.endpoint.health.group.demo.include=readinessState"})
@ActiveProfiles("bundled-ui")
@AutoConfigureMockMvc
@Testcontainers
class BundledUiIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired TestRestTemplate http;

    @Test void servesAnonymousDocumentsAndAssetsWithSecurityHeaders() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/orders", "/inventory", "/reports"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"root\"")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        }
        mvc.perform(get("/assets/fixture.js")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("UI fixture")));
        mvc.perform(head("/orders")).andExpect(status().isOk());
    }

    @Test void doesNotFallbackForApiActuatorOrMissingResources() throws Exception {
        for (String path : new String[]{"/api/v1/orders", "/api/unknown", "/actuator/env", "/v3/api-docs"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"root\""))));
        }
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(post("/orders")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test void exposesMinimalReadinessAndSecureCsrfSessionCookie() throws Exception {
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/demo"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        }
        var response = http.getForEntity("/api/v1/auth/csrf", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
            .contains("HttpOnly", "Secure", "SameSite=Strict");
        assertThat(response.getBody()).contains("headerName", "token");
    }
}

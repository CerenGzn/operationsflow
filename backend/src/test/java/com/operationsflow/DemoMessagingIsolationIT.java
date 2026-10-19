package com.operationsflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ServiceConnection binds ALL database access to disposable PostgreSQL, never the synthetic hostname. */
@SpringBootTest(properties = {"DEMO_DB_HOST=synthetic.neon.tech", "DEMO_DB_NAME=synthetic", "DEMO_HTTPS_EDGE=true"})
@ActiveProfiles({"demo", "messaging"})
@AutoConfigureMockMvc
@Testcontainers
class DemoMessagingIsolationIT extends MessagingTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Autowired ApplicationContext context;
    @Test void fullDemoContextOverridesMessagingAndOperatesWithoutBrokerCredentials() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        assertThat(context.getBeansOfType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.amqp.rabbit.core.RabbitTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(com.operationsflow.messaging.application.OutboxPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(com.operationsflow.messaging.infrastructure.MessagingConfiguration.Poller.class)).isEmpty();
        assertThat(context.getBeansOfType(com.operationsflow.messaging.api.MessagingStatusController.class)).isEmpty();
        var order = create();
        orders.cancel(order.id(),new com.operationsflow.orders.api.OrderContracts.Cancel("Synthetic demo cancellation"));
        assertThat(count("event_outbox")).isEqualTo(2);
        assertThat(outbox.counts()).containsOnlyKeys("PENDING");
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.secure",Boolean.class)).isTrue();
    }
}

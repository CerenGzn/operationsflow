package com.operationsflow;

import java.time.Duration;
import java.util.*;
import com.operationsflow.messaging.application.OutboxPublisher;
import com.operationsflow.messaging.domain.OrderEvent;
import com.operationsflow.messaging.infrastructure.*;
import com.operationsflow.security.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.operationsflow.messaging.infrastructure.MessagingConfiguration.*;

@SpringBootTest(properties = {"operationsflow.messaging.publisher-enabled=false", "operationsflow.messaging.listener-enabled=false", "operationsflow.messaging.confirm-timeout-ms=1000"})
@ActiveProfiles("messaging")
@AutoConfigureMockMvc
@Testcontainers
class MessagingBrokerIT extends MessagingTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Container static final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:4.3.6-management-alpine");
    @DynamicPropertySource static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("operationsflow.messaging.host",broker::getHost);
        registry.add("operationsflow.messaging.port",broker::getAmqpPort);
        registry.add("operationsflow.messaging.username",broker::getAdminUsername);
        registry.add("operationsflow.messaging.password",broker::getAdminPassword);
    }
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired CachingConnectionFactory connection;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ConfirmedSender sender;
    @Autowired OutboxPublisher publisher;
    @BeforeEach void prepareBroker() {
        listeners.stop(); admin.initialize(); admin.purgeQueue(QUEUE); admin.purgeQueue(DLQ);
    }
    @AfterEach void stopConsumers() { listeners.stop(); }
    private void sendRaw(byte[] body) {
        var props = new MessageProperties(); props.setContentType("application/json"); props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(EXCHANGE,"ORDER_RESERVED",new Message(body,props));
    }
    private Message awaitDeadLetter() {
        var found = new java.util.concurrent.atomic.AtomicReference<Message>();
        await().atMost(Duration.ofSeconds(30)).until(() -> { found.set(rabbit.receive(DLQ)); return found.get()!=null; });
        return found.get();
    }
    @Test void confirmsPublishAndDuplicateDeliveryProducesOneLedgerEntry() throws Exception {
        create(); var claim = outbox.claim().orElseThrow();
        assertThat(sender.send(claim.event())).isEqualTo(ConfirmedSender.Result.CONFIRMED);
        assertThat(outbox.published(claim)).isTrue();
        assertThat(sender.send(claim.event())).isEqualTo(ConfirmedSender.Result.CONFIRMED);
        listeners.start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(count("order_event_ledger")).isEqualTo(1); assertThat(count("event_inbox")).isEqualTo(1);
            assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
        });
        assertThat(state(claim.event().eventId())).isEqualTo("PUBLISHED");
    }
    @Test void mandatoryReturnLeavesOutboxRetryable() {
        var order = create();
        var binding = new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,"ORDER_RESERVED",null);
        admin.removeBinding(binding);
        try {
            assertThat(publisher.publishOne()).isTrue();
            assertThat(jdbc.queryForObject("select state from operationsflow.event_outbox where order_id=?",String.class,order.id())).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select last_error from operationsflow.event_outbox where order_id=?",String.class,order.id())).isEqualTo("UNROUTABLE");
        } finally { admin.declareBinding(binding); }
    }
    @Test void brokerDowntimeDoesNotBlockBusinessAndRecoveryPublishes() throws Exception {
        var stopped = broker.execInContainer("rabbitmqctl","stop_app"); assertThat(stopped.getExitCode()).isZero();
        UUID order;
        try {
            connection.resetConnection(); order = create().id();
            assertThat(publisher.publishOne()).isTrue();
            assertThat(jdbc.queryForObject("select state from operationsflow.event_outbox where order_id=?",String.class,order)).isEqualTo("PENDING");
        } finally {
            assertThat(broker.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();
            connection.resetConnection(); admin.initialize();
        }
        jdbc.update("update operationsflow.event_outbox set next_attempt_at=now()-interval '1 second'");
        assertThat(publisher.publishOne()).isTrue();
        assertThat(jdbc.queryForObject("select state from operationsflow.event_outbox where order_id=?",String.class,order)).isEqualTo("PUBLISHED");
    }
    @Test void processCrashAfterConfirmBeforeDatabaseAcknowledgmentIsSafe() {
        create(); var abandoned = outbox.claim().orElseThrow();
        assertThat(sender.send(abandoned.event())).isEqualTo(ConfirmedSender.Result.CONFIRMED);
        // Simulate process loss after the durable broker ACK but before marking the row published.
        jdbc.update("update operationsflow.event_outbox set lease_until=now()-interval '1 second'");
        assertThat(publisher.publishOne()).isTrue(); listeners.start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("order_event_ledger")).isEqualTo(1));
        assertThat(outbox.published(abandoned)).isFalse();
        assertThat(state(abandoned.event().eventId())).isEqualTo("PUBLISHED");
    }
    @Test void poisonEnvelopeAndUnsupportedVersionAreDeadLettered() throws Exception {
        create(); var event = outbox.claim().orElseThrow().event();
        listeners.start(); sendRaw("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(awaitDeadLetter().getBody()).isEqualTo("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var body = json.valueToTree(event); ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("schemaVersion",99);
        sendRaw(json.writeValueAsBytes(body)); assertThat(awaitDeadLetter()).isNotNull();
        assertThat(count("event_inbox")).isZero(); assertThat(count("order_event_ledger")).isZero();
    }
    @Test void failedConsumerTransactionRollsBackInboxAndCanBeReplayedAfterRepair() throws Exception {
        create(); var event = outbox.claim().orElseThrow().event();
        jdbc.execute("alter table operationsflow.order_event_ledger add constraint test_reject_event check (total_units < 0) not valid");
        Message dead;
        try {
            listeners.start(); sendRaw(json.writeValueAsBytes(event)); dead = awaitDeadLetter();
            assertThat(count("event_inbox")).isZero(); assertThat(count("order_event_ledger")).isZero();
        } finally { jdbc.execute("alter table operationsflow.order_event_ledger drop constraint test_reject_event"); }
        sendRaw(dead.getBody());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("order_event_ledger")).isEqualTo(1));
        assertThat(count("event_inbox")).isEqualTo(1);
    }
    @Test void diagnosticsAreAdminOnlyAndDoNotExposeEventPayloads() throws Exception {
        create();
        mvc.perform(get("/api/v1/messaging/status")).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outbox.PENDING").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.processedEvents").value(0))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.payload").doesNotExist());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var secured = MockMvcBuilders.webAppContextSetup(authContext).apply(springSecurity()).build();
        secured.perform(get("/api/v1/messaging/status")).andExpect(status().isUnauthorized());
        for (var role : List.of(Role.SALES_OPERATOR,Role.WAREHOUSE_OPERATOR,Role.DEMO_VIEWER)) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into operationsflow.app_user(id,email,display_name,password_hash,role,must_change_password) select ?,?,'Synthetic messaging test',password_hash,?,false from operationsflow.app_user where id=?",id,id+"@example.invalid",role.name(),TEST_ADMIN);
            var auth = UsernamePasswordAuthenticationToken.authenticated(new SessionActor(id,role,0),null,List.of(new SimpleGrantedAuthority("ROLE_"+role.name())));
            secured.perform(get("/api/v1/messaging/status").with(authentication(auth))).andExpect(status().isForbidden());
        }
    }
}

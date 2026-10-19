package com.operationsflow.messaging.infrastructure;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.application.EventConsumer;
import com.operationsflow.messaging.application.OutboxPublisher;
import com.operationsflow.messaging.domain.OrderEvent;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("messaging & !demo")
@EnableRabbit
@EnableScheduling
public class MessagingConfiguration {
    public static final String EXCHANGE = "operationsflow.orders.v1";
    public static final String QUEUE = "operationsflow.order-ledger.v1";
    public static final String DLX = "operationsflow.orders.dead.v1";
    public static final String DLQ = "operationsflow.order-ledger.dead.v1";

    @Bean CachingConnectionFactory rabbitConnectionFactory(Environment env) {
        var factory = new CachingConnectionFactory(env.getProperty("operationsflow.messaging.host", "localhost"),
            env.getProperty("operationsflow.messaging.port", Integer.class, 5672));
        factory.setUsername(env.getRequiredProperty("operationsflow.messaging.username"));
        factory.setPassword(env.getRequiredProperty("operationsflow.messaging.password"));
        factory.setConnectionTimeout(3000);
        factory.getRabbitConnectionFactory().setHandshakeTimeout(3000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        factory.setRequestedHeartBeat(15);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }
    @Bean RabbitTemplate rabbitTemplate(CachingConnectionFactory factory) {
        var template = new RabbitTemplate(factory); template.setMandatory(true); return template;
    }
    @Bean RabbitAdmin rabbitAdmin(CachingConnectionFactory factory) { return new RabbitAdmin(factory); }
    @Bean Declarables topology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var dead = new DirectExchange(DLX, true, false);
        var queue = QueueBuilder.durable(QUEUE).quorum()
            .deadLetterExchange(DLX).deadLetterRoutingKey("rejected")
            .withArgument("x-dead-letter-strategy", "at-least-once")
            .withArgument("x-overflow", "reject-publish").withArgument("x-delivery-limit", 5).build();
        var dlq = QueueBuilder.durable(DLQ).quorum().build();
        List<Declarable> declarations = new ArrayList<>(List.of(exchange, dead, queue, dlq,
            BindingBuilder.bind(dlq).to(dead).with("rejected")));
        for (var type : OrderEvent.Type.values()) declarations.add(BindingBuilder.bind(queue).to(exchange).with(type.name()));
        return new Declarables(declarations);
    }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory factory, Environment env) {
        var listener = new SimpleRabbitListenerContainerFactory();
        listener.setConnectionFactory(factory);
        listener.setConcurrentConsumers(1); listener.setPrefetchCount(10);
        listener.setAutoStartup(env.getProperty("operationsflow.messaging.listener-enabled", Boolean.class, true));
        listener.setDefaultRequeueRejected(false); listener.setMissingQueuesFatal(false);
        listener.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(100, 2, 1000)
            .recoverer((message, cause) -> {
                LoggerFactory.getLogger(MessagingConfiguration.class).warn("messaging consumer_rejected retries_exhausted");
                throw new AmqpRejectAndDontRequeueException("Event processing exhausted");
            }).build());
        // Avoid the default handler's payload-bearing ListenerExecutionFailedException log.
        listener.setErrorHandler(error -> { throw new AmqpRejectAndDontRequeueException("Event rejected"); });
        return listener;
    }
    @Bean EventListener eventListener(ObjectMapper json, EventConsumer consumer) { return new EventListener(json, consumer); }
    public static class EventListener {
        private final ObjectMapper json; private final EventConsumer consumer;
        EventListener(ObjectMapper json, EventConsumer consumer) { this.json = json; this.consumer = consumer; }
        @RabbitListener(queues = QUEUE)
        public void receive(Message message) throws java.io.IOException {
            if (message.getBody().length > 16384 || !MessageProperties.CONTENT_TYPE_JSON.equals(message.getMessageProperties().getContentType()))
                throw new IllegalArgumentException("Invalid event envelope");
            consumer.accept(json.readValue(message.getBody(), OrderEvent.class));
        }
    }
    @Bean
    @ConditionalOnProperty(name = "operationsflow.messaging.publisher-enabled", havingValue = "true", matchIfMissing = true)
    Poller outboxPoller(OutboxPublisher publisher) { return new Poller(publisher); }
    public static class Poller {
        private final OutboxPublisher publisher;
        Poller(OutboxPublisher publisher) { this.publisher = publisher; }
        @Scheduled(fixedDelayString = "${operationsflow.messaging.poll-ms:1000}", initialDelay = 1000)
        public void poll() {
            try { publisher.publishOne(); }
            catch (RuntimeException ex) { LoggerFactory.getLogger(Poller.class).warn("outbox poll_failed retry_next_poll"); }
        }
    }
}

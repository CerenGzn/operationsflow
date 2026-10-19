package com.operationsflow.messaging.infrastructure;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.domain.OrderEvent;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("messaging & !demo")
public class ConfirmedSender {
    public enum Result { CONFIRMED, UNROUTABLE, NACK, UNCONFIRMED }
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final long timeout;
    public ConfirmedSender(RabbitTemplate rabbit, ObjectMapper json,
            @Value("${operationsflow.messaging.confirm-timeout-ms:5000}") long timeout) {
        if (timeout < 1 || timeout > 10000) throw new IllegalArgumentException("Invalid confirmation timeout");
        this.rabbit = rabbit; this.json = json; this.timeout = timeout;
    }
    public Result send(OrderEvent event) {
        try {
            var properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(event.eventId().toString());
            properties.setCorrelationId(event.correlationId().toString());
            var correlation = new CorrelationData(event.eventId() + ":" + UUID.randomUUID());
            rabbit.send(MessagingConfiguration.EXCHANGE, event.eventType().name(),
                new Message(json.writeValueAsBytes(event), properties), correlation);
            var confirm = correlation.getFuture().get(timeout, TimeUnit.MILLISECONDS);
            // A mandatory return precedes the confirm: ACK alone does not mean routing succeeded.
            if (correlation.getReturned() != null) return Result.UNROUTABLE;
            return confirm.isAck() ? Result.CONFIRMED : Result.NACK;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return Result.UNCONFIRMED;
        } catch (Exception ex) {
            // Do not log transport exception text, credentials, or event bodies.
            return Result.UNCONFIRMED;
        }
    }
}

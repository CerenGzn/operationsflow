package com.operationsflow;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operationsflow.messaging.domain.OrderEvent;
import com.operationsflow.messaging.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmedSenderTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ConfirmedSender sender = new ConfirmedSender(rabbit, json, 10);
    private OrderEvent event() { var id = UUID.randomUUID(); return new OrderEvent(UUID.randomUUID(), OrderEvent.Type.ORDER_RESERVED, 1, id, Instant.now(), id, new OrderEvent.Payload(3)); }
    private void reply(boolean ack, boolean returned) {
        doAnswer(call -> {
            Message message = call.getArgument(2); CorrelationData data = call.getArgument(3);
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(json.readTree(message.getBody()).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("eventId", "eventType", "schemaVersion", "orderId", "occurredAt", "correlationId", "payload");
            if (returned) data.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", MessagingConfiguration.EXCHANGE, "ORDER_RESERVED"));
            data.getFuture().complete(new CorrelationData.Confirm(ack, null)); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    @Test void acknowledgedPersistentEnvelopeSucceeds() { reply(true, false); assertThat(sender.send(event())).isEqualTo(ConfirmedSender.Result.CONFIRMED); }
    @Test void returnedMessageIsFailureEvenWithAck() { reply(true, true); assertThat(sender.send(event())).isEqualTo(ConfirmedSender.Result.UNROUTABLE); }
    @Test void nackIsFailure() { reply(false, false); assertThat(sender.send(event())).isEqualTo(ConfirmedSender.Result.NACK); }
    @Test void missingConfirmationTimesOut() { assertThat(sender.send(event())).isEqualTo(ConfirmedSender.Result.UNCONFIRMED); }
    @Test void connectionFailureIsNotSuccess() {
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException())).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(sender.send(event())).isEqualTo(ConfirmedSender.Result.UNCONFIRMED);
    }
    @Test void contractRejectsUnsupportedVersionAndInvalidQuantity() {
        assertThatThrownBy(() -> new OrderEvent.Payload(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderEvent.Payload(50_000_000_001L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderEvent(UUID.randomUUID(), OrderEvent.Type.ORDER_RESERVED, 2, UUID.randomUUID(), Instant.now(), UUID.randomUUID(), new OrderEvent.Payload(1))).isInstanceOf(IllegalArgumentException.class);
    }
}

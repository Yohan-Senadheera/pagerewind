package dev.pagerewind.incident;

import dev.pagerewind.events.Messaging;
import dev.pagerewind.incident.OutboxRepository.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes with publisher confirms. Incident events are broadcast: if no service listens for an
 * event type yet, the broker drops it, and that is not an error.
 */
@Component
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbit;
    private final IncidentProperties properties;

    public RabbitMessagePublisher(RabbitTemplate rabbit, IncidentProperties properties) {
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxMessage outboxMessage) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(outboxMessage.eventId().toString());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader(Messaging.EVENT_TYPE_HEADER, outboxMessage.eventType());
        Message message = new Message(outboxMessage.payload().getBytes(StandardCharsets.UTF_8), props);

        CorrelationData correlation = new CorrelationData(outboxMessage.eventId().toString());
        rabbit.send(Messaging.EVENTS_EXCHANGE, outboxMessage.routingKey(), message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("Broker refused event " + outboxMessage.eventId() + ": " + confirm.reason());
            }
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for broker confirm of event " + outboxMessage.eventId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Publishing event " + outboxMessage.eventId() + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + outboxMessage.eventId(), e);
        }
    }
}

package dev.pagerewind.messaging;

import dev.pagerewind.events.Messaging;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes with publisher confirms (needs {@code spring.rabbitmq.publisher-confirm-type=correlated}).
 * Events are broadcast: if no service listens for an event type yet, the broker drops it, and that
 * is not an error.
 */
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbit;
    private final Duration timeout;

    public RabbitMessagePublisher(RabbitTemplate rabbit, Duration timeout) {
        this.rabbit = rabbit;
        this.timeout = timeout;
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
            CorrelationData.Confirm confirm = correlation.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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

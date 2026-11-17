package dev.pagerewind.ingest;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.Messaging;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes events and waits for RabbitMQ to confirm each one.
 *
 * <p>A request only succeeds once the broker has stored every message, so an alert is never
 * acknowledged to the sender and then lost. Unroutable messages (no queue bound) count as failures.
 */
@Component
public class EventPublisher {

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final IngestProperties properties;

    public EventPublisher(RabbitTemplate rabbit, JsonMapper json, IngestProperties properties) {
        this.rabbit = rabbit;
        this.json = json;
        this.properties = properties;
    }

    public void publishAll(List<AlertReceived> events) {
        List<CorrelationData> pending = new ArrayList<>(events.size());
        for (AlertReceived event : events) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setMessageId(event.eventId().toString());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setHeader(Messaging.EVENT_TYPE_HEADER, event.type().name());
            Message message = new Message(json.writeValueAsBytes(event), props);

            CorrelationData correlation = new CorrelationData(event.eventId().toString());
            rabbit.send(Messaging.EVENTS_EXCHANGE, event.type().routingKey(), message, correlation);
            pending.add(correlation);
        }
        long deadline = System.nanoTime() + properties.publishTimeout().toNanos();
        for (CorrelationData correlation : pending) {
            awaitConfirm(correlation, deadline);
        }
    }

    private static void awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        try {
            long wait = Math.max(0, deadlineNanos - System.nanoTime());
            CorrelationData.Confirm confirm = correlation.getFuture().get(wait, TimeUnit.NANOSECONDS);
            if (!confirm.ack()) {
                throw new PublishFailedException("Broker refused event " + correlation.getId() + ": " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new PublishFailedException("No queue is bound for event " + correlation.getId());
            }
        } catch (TimeoutException e) {
            throw new PublishFailedException("Timed out waiting for broker confirm of event " + correlation.getId());
        } catch (ExecutionException e) {
            throw new PublishFailedException("Publishing event " + correlation.getId() + " failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while publishing event " + correlation.getId());
        }
    }

    public static class PublishFailedException extends RuntimeException {
        PublishFailedException(String message) {
            super(message);
        }
    }
}

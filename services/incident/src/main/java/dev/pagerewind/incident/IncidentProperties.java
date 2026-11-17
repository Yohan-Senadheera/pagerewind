package dev.pagerewind.incident;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param alertsQueue     queue this service reads alerts from
 * @param deliveryLimit   how many times a failed message is redelivered before it goes to the
 *                        dead-letter queue (total attempts = deliveryLimit + 1)
 * @param outboxBatchSize most outbox rows published per relay run
 * @param publishTimeout  how long to wait for RabbitMQ to confirm a message
 */
@ConfigurationProperties("pagerewind.incident")
public record IncidentProperties(
        @DefaultValue("incident.alerts") String alertsQueue,
        @DefaultValue("4") int deliveryLimit,
        @DefaultValue("50") int outboxBatchSize,
        @DefaultValue("5s") Duration publishTimeout) {

    public String alertsDeadLetterQueue() {
        return alertsQueue + ".dlq";
    }
}

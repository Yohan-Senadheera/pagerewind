package dev.pagerewind.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param alertsQueue   queue this service reads alerts from
 * @param evidenceQueue queue this service reads evidence notices from (capture service)
 * @param deliveryLimit how many times a failed message is redelivered before it goes to the
 *                      dead-letter queue (total attempts = deliveryLimit + 1)
 */
@ConfigurationProperties("pagerewind.incident")
public record IncidentProperties(
        @DefaultValue("incident.alerts") String alertsQueue,
        @DefaultValue("incident.evidence") String evidenceQueue,
        @DefaultValue("4") int deliveryLimit) {

    public String alertsDeadLetterQueue() {
        return alertsQueue + ".dlq";
    }

    public String evidenceDeadLetterQueue() {
        return evidenceQueue + ".dlq";
    }
}

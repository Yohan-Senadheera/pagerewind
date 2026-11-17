package dev.pagerewind.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param eventsQueue         queue for incident events this service reacts to
 * @param deliveryLimit       redeliveries before a failing message goes to the dead-letter queue
 * @param escalationBatchSize most due escalation jobs handled per run
 */
@ConfigurationProperties("pagerewind.schedule")
public record ScheduleProperties(
        @DefaultValue("schedule.incident-events") String eventsQueue,
        @DefaultValue("4") int deliveryLimit,
        @DefaultValue("50") int escalationBatchSize) {

    public String eventsDeadLetterQueue() {
        return eventsQueue + ".dlq";
    }
}

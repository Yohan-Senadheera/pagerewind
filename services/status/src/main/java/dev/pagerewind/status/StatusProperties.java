package dev.pagerewind.status;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param title    heading of the public page
 * @param services    services always listed, even before they ever had an incident
 * @param historyDays how many days of past incidents the page lists
 */
@ConfigurationProperties("pagerewind.status")
public record StatusProperties(
        @DefaultValue("status.incident-events") String eventsQueue,
        @DefaultValue("4") int deliveryLimit,
        @DefaultValue("System status") String title,
        @DefaultValue List<String> services,
        @DefaultValue("14") int historyDays) {

    public StatusProperties {
        services = services == null ? List.of() : services.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public String eventsDeadLetterQueue() {
        return eventsQueue + ".dlq";
    }
}

package dev.pagerewind.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publishTimeout how long to wait for RabbitMQ to confirm a message
 */
@ConfigurationProperties("pagerewind.messaging")
public record MessagingProperties(@DefaultValue Outbox outbox, @DefaultValue("5s") Duration publishTimeout) {

    /**
     * @param relayEnabled   run the relay in the background (tests turn it off and call it directly)
     * @param batchSize      most rows published per relay run
     * @param pollIntervalMs pause between relay runs
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("50") int batchSize,
            @DefaultValue("500") long pollIntervalMs) {
    }
}

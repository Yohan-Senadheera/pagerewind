package dev.pagerewind.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the outbox relay in the background. Turned off in tests that drive the relay directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pagerewind.incident.outbox-relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxRelay relay;
    private final IncidentProperties properties;

    OutboxScheduler(OutboxRelay relay, IncidentProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pagerewind.incident.outbox-poll-interval-ms:500}")
    void relay() {
        try {
            while (relay.relayOnce() == properties.outboxBatchSize()) {
                // A full batch means more rows may be waiting.
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }
}

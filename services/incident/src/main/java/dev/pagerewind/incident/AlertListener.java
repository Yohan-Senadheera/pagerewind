package dev.pagerewind.incident;

import dev.pagerewind.events.AlertReceived;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code alert.received} events. If handling fails, the message is returned to the queue; after
 * the queue's delivery limit it goes to the dead-letter queue.
 */
@Component
class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final IncidentService service;
    private final JsonMapper json;
    private final Counter failures;

    AlertListener(IncidentService service, JsonMapper json, MeterRegistry meters) {
        this.service = service;
        this.json = json;
        this.failures = Counter.builder("pagerewind.incident.alerts.failed")
                .description("Attempts to handle an alert message that failed")
                .register(meters);
    }

    @RabbitListener(queues = "${pagerewind.incident.alerts-queue:incident.alerts}")
    void onAlert(Message message) {
        try {
            AlertReceived alert = json.readValue(message.getBody(), AlertReceived.class);
            service.handleAlert(alert);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Handling alert message {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }
}

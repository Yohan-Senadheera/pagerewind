package dev.pagerewind.schedule;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.IncidentAcknowledged;
import dev.pagerewind.events.IncidentResolved;
import dev.pagerewind.events.IncidentTriggered;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class IncidentEventListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventListener.class);

    private final EscalationService service;
    private final JsonMapper json;
    private final Counter failures;

    IncidentEventListener(EscalationService service, JsonMapper json, MeterRegistry meters) {
        this.service = service;
        this.json = json;
        this.failures = Counter.builder("pagerewind.schedule.events.failed")
                .description("Attempts to handle an incident event that failed").register(meters);
    }

    @RabbitListener(queues = "${pagerewind.schedule.events-queue:schedule.incident-events}")
    void onEvent(Message message) {
        try {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            EventType type = EventType.fromRoutingKey(routingKey)
                    .orElseThrow(() -> new IllegalArgumentException("Unexpected routing key " + routingKey));
            switch (type) {
                case INCIDENT_TRIGGERED -> service.onTriggered(json.readValue(message.getBody(), IncidentTriggered.class));
                case INCIDENT_ACKNOWLEDGED -> {
                    IncidentAcknowledged e = json.readValue(message.getBody(), IncidentAcknowledged.class);
                    service.onStopped(e.eventId(), e.incidentId(), "ACKNOWLEDGED");
                }
                case INCIDENT_RESOLVED -> {
                    IncidentResolved e = json.readValue(message.getBody(), IncidentResolved.class);
                    service.onStopped(e.eventId(), e.incidentId(), "RESOLVED");
                }
                default -> throw new IllegalArgumentException("Unexpected event type " + type);
            }
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Handling incident event {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }
}

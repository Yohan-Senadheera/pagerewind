package dev.pagerewind.incident;

import dev.pagerewind.events.EvidenceCaptured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads {@code evidence.captured} events from the capture service into the incident timeline. */
@Component
class EvidenceListener {

    private static final Logger log = LoggerFactory.getLogger(EvidenceListener.class);

    private final IncidentService service;
    private final JsonMapper json;

    EvidenceListener(IncidentService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @RabbitListener(queues = "${pagerewind.incident.evidence-queue:incident.evidence}")
    void onEvidence(Message message) {
        try {
            service.recordEvidence(json.readValue(message.getBody(), EvidenceCaptured.class));
        } catch (RuntimeException e) {
            log.warn("Handling evidence message {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }
}

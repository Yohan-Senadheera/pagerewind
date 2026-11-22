package dev.pagerewind.events;

import java.util.Arrays;
import java.util.Optional;

/** Every event type, with the routing key it is published under. */
public enum EventType {

    ALERT_RECEIVED("alert.received", AlertReceived.class),
    INCIDENT_TRIGGERED("incident.triggered", IncidentTriggered.class),
    INCIDENT_ACKNOWLEDGED("incident.acknowledged", IncidentAcknowledged.class),
    INCIDENT_RESOLVED("incident.resolved", IncidentResolved.class),
    PAGE_REQUESTED("page.requested", PageRequested.class),
    EVIDENCE_CAPTURED("evidence.captured", EvidenceCaptured.class);

    private final String routingKey;
    private final Class<? extends PageRewindEvent> eventClass;

    EventType(String routingKey, Class<? extends PageRewindEvent> eventClass) {
        this.routingKey = routingKey;
        this.eventClass = eventClass;
    }

    public String routingKey() {
        return routingKey;
    }

    public Class<? extends PageRewindEvent> eventClass() {
        return eventClass;
    }

    public static EventType of(PageRewindEvent event) {
        return switch (event) {
            case AlertReceived e -> ALERT_RECEIVED;
            case IncidentTriggered e -> INCIDENT_TRIGGERED;
            case IncidentAcknowledged e -> INCIDENT_ACKNOWLEDGED;
            case IncidentResolved e -> INCIDENT_RESOLVED;
            case PageRequested e -> PAGE_REQUESTED;
            case EvidenceCaptured e -> EVIDENCE_CAPTURED;
        };
    }

    public static Optional<EventType> fromRoutingKey(String routingKey) {
        return Arrays.stream(values()).filter(t -> t.routingKey.equals(routingKey)).findFirst();
    }
}

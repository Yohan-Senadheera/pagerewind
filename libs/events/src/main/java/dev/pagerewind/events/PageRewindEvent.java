package dev.pagerewind.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape of every event that services exchange through RabbitMQ.
 *
 * <p>{@code eventId} is unique per event and is what consumers use to process each event once.
 * {@code version} is the schema version of the event type, so consumers can handle old and new
 * shapes during a rolling upgrade.
 */
public sealed interface PageRewindEvent
        permits AlertReceived, IncidentTriggered, IncidentAcknowledged, IncidentResolved, PageRequested,
        EvidenceCaptured {

    UUID eventId();

    Instant occurredAt();

    int version();

    default EventType type() {
        return EventType.of(this);
    }
}

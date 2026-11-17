package dev.pagerewind.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentAcknowledged(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        String actor) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public IncidentAcknowledged {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonNull(incidentId, "incidentId");
        Require.nonBlank(actor, "actor");
    }
}

package dev.pagerewind.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentTriggered(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        String fingerprint,
        String service,
        Severity severity,
        String summary) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public IncidentTriggered {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonNull(incidentId, "incidentId");
        Require.nonBlank(fingerprint, "fingerprint");
        Require.nonBlank(service, "service");
        Require.nonNull(severity, "severity");
        Require.nonBlank(summary, "summary");
    }
}

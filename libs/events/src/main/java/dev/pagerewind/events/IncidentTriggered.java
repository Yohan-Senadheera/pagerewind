package dev.pagerewind.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An incident was opened.
 *
 * @param labels the labels of the alert that opened it (for example {@code namespace}, {@code pod},
 *               {@code deployment}), so other services can find the workload. Empty for events from
 *               older versions, which did not have this field.
 */
public record IncidentTriggered(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        String fingerprint,
        String service,
        Severity severity,
        String summary,
        Map<String, String> labels) implements PageRewindEvent {

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
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public IncidentTriggered(UUID eventId, Instant occurredAt, int version, UUID incidentId, String fingerprint,
            String service, Severity severity, String summary) {
        this(eventId, occurredAt, version, incidentId, fingerprint, service, severity, summary, Map.of());
    }
}

package dev.pagerewind.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The flight recorder froze the evidence for an incident: recent logs of the matching containers and the
 * ranked changes before the alert. The bundle itself stays in {@code capture}; this event carries what
 * the incident timeline shows.
 *
 * @param likelyCause the top-ranked change with its reason, or null if nothing changed before the alert
 */
public record EvidenceCaptured(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        int containers,
        int logLines,
        int changes,
        long bundleBytes,
        String likelyCause) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public EvidenceCaptured {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonNull(incidentId, "incidentId");
        if (containers < 0 || logLines < 0 || changes < 0 || bundleBytes < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        likelyCause = likelyCause == null || likelyCause.isBlank() ? null : likelyCause;
    }
}

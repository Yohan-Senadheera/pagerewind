package dev.pagerewind.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One alert, normalised from whatever tool sent it.
 *
 * @param fingerprint stable identity of the alert; the same problem always has the same
 *                    fingerprint, which is how alerts are grouped into one incident
 * @param repeat      true when the same fingerprint was already seen within the duplicate window.
 *                    Downstream services may skip expensive work for repeats, but correctness
 *                    never depends on this flag
 */
public record AlertReceived(
        UUID eventId,
        Instant occurredAt,
        int version,
        String source,
        String service,
        Severity severity,
        AlertStatus status,
        String summary,
        Map<String, String> labels,
        String fingerprint,
        boolean repeat,
        Instant startsAt) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public AlertReceived {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonBlank(source, "source");
        Require.nonBlank(service, "service");
        Require.nonNull(severity, "severity");
        Require.nonNull(status, "status");
        Require.nonBlank(summary, "summary");
        Require.nonBlank(fingerprint, "fingerprint");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}

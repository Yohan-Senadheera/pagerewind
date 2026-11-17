package dev.pagerewind.incident;

import dev.pagerewind.events.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Incident(
        UUID id,
        String fingerprint,
        String service,
        Severity severity,
        String summary,
        IncidentStatus status,
        int alertCount,
        Map<String, String> labels,
        Instant createdAt,
        Instant updatedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        long version) {
}

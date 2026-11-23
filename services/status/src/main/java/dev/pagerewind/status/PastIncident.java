package dev.pagerewind.status;

import dev.pagerewind.events.Severity;
import java.time.Duration;
import java.time.Instant;

/**
 * An incident for the public history. Only the service, the impact and the times: never the alert text,
 * labels or incident id, which can reveal internals.
 *
 * @param resolvedAt null while the incident is still open
 */
public record PastIncident(String service, ServiceStatus.Level impact, String impactLabel, Instant openedAt,
        Instant resolvedAt, String duration) {

    static PastIncident of(String service, Severity severity, Instant openedAt, Instant resolvedAt) {
        ServiceStatus.Level impact = severity == Severity.CRITICAL ? ServiceStatus.Level.MAJOR_OUTAGE : ServiceStatus.Level.DEGRADED;
        String duration = openedAt == null || resolvedAt == null ? null : human(Duration.between(openedAt, resolvedAt));
        return new PastIncident(service, impact, impact.label(), openedAt, resolvedAt, duration);
    }

    static String human(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if (minutes < 1) {
            return "under a minute";
        }
        if (minutes < 60) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + (hours == 1 ? " hour" : " hours") + (rest == 0 ? "" : " " + rest + (rest == 1 ? " minute" : " minutes"));
    }
}

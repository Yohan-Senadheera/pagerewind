package dev.pagerewind.incident;

import java.time.Instant;
import java.util.Map;

public record TimelineEntry(long id, Type type, String message, String actor, Map<String, Object> data, Instant occurredAt) {

    public enum Type {
        TRIGGERED,
        ALERT,
        ALERT_RESOLVED,
        ACKNOWLEDGED,
        RESOLVED,
        NOTE,
        /** The flight recorder froze logs and recent changes for this incident. */
        EVIDENCE
    }
}

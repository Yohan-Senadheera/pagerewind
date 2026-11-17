package dev.pagerewind.schedule;

import java.time.Instant;

/** Someone covers a rotation for a period. When overrides overlap, the newest one wins. */
public record RotationOverride(long id, String rotationId, String personId, Instant start, Instant end) {

    boolean covers(Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }
}

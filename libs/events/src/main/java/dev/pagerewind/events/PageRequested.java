package dev.pagerewind.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Ask the notify service to page someone.
 *
 * @param step   escalation step, starting at 1
 * @param target who to page, for example a user or schedule reference
 */
public record PageRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        int step,
        String target) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public PageRequested {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonNull(incidentId, "incidentId");
        Require.positive(step, "step");
        Require.nonBlank(target, "target");
    }
}

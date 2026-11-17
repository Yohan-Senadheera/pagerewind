package dev.pagerewind.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ask the notify service to page one person. The event carries everything needed to send the
 * page, so notify does not have to call other services.
 *
 * @param step  escalation step, starting at 1
 * @param round repeat round of the escalation policy, starting at 1
 */
public record PageRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID incidentId,
        int step,
        int round,
        String personId,
        String personName,
        List<Contact> contacts,
        String service,
        Severity severity,
        String summary) implements PageRewindEvent {

    public static final int CURRENT_VERSION = 1;

    public PageRequested {
        Require.nonNull(eventId, "eventId");
        Require.nonNull(occurredAt, "occurredAt");
        Require.positive(version, "version");
        Require.nonNull(incidentId, "incidentId");
        Require.positive(step, "step");
        Require.positive(round, "round");
        Require.nonBlank(personId, "personId");
        Require.nonBlank(personName, "personName");
        Require.nonNull(severity, "severity");
        Require.nonBlank(service, "service");
        Require.nonBlank(summary, "summary");
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
    }
}

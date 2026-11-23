package dev.pagerewind.incident;

import java.time.Instant;
import java.util.List;

/**
 * How often the same alert arrived for one incident, with the exact times. Built from the incident's
 * ALERT timeline entries: one entry per alert received, including repeats of the same alert.
 *
 * @param times newest first
 */
record Occurrences(int count, Instant first, Instant last, List<Occurrence> times) {

    /**
     * @param receivedAt when PageRewind received it
     * @param startedAt  when the alert started at its source (Alertmanager's startsAt), if known
     * @param repeat     the sender marked it as a repeat of an alert it had already sent
     */
    record Occurrence(int number, Instant receivedAt, Instant startedAt, boolean repeat) {
    }

    static Occurrences of(List<TimelineEntry> timeline) {
        List<TimelineEntry> alerts = timeline.stream().filter(e -> e.type() == TimelineEntry.Type.ALERT).toList();
        List<Occurrence> times = new java.util.ArrayList<>();
        for (int i = 0; i < alerts.size(); i++) {
            TimelineEntry alert = alerts.get(i);
            times.add(new Occurrence(i + 1, alert.occurredAt(), startedAt(alert), Boolean.TRUE.equals(alert.data().get("repeat"))));
        }
        java.util.Collections.reverse(times);
        Instant first = alerts.isEmpty() ? null : alerts.getFirst().occurredAt();
        Instant last = alerts.isEmpty() ? null : alerts.getLast().occurredAt();
        return new Occurrences(alerts.size(), first, last, List.copyOf(times));
    }

    private static Instant startedAt(TimelineEntry alert) {
        Object value = alert.data().get("startsAt");
        try {
            return value == null ? null : Instant.parse(value.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }
}

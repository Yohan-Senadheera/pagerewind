package dev.pagerewind.incident;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.EvidenceCaptured;
import dev.pagerewind.events.IncidentAcknowledged;
import dev.pagerewind.events.IncidentResolved;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.PageRewindEvent;
import dev.pagerewind.messaging.OutboxRepository;
import dev.pagerewind.messaging.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    /** What happened to an incoming alert. */
    public enum AlertOutcome {
        /** A new incident was opened. */
        CREATED,
        /** The alert was added to the open incident with the same fingerprint. */
        ATTACHED,
        /** A "resolved" alert was recorded on the open incident. */
        RESOLVED_RECORDED,
        /** A "resolved" alert arrived but no incident was open. */
        RESOLVED_IGNORED,
        /** This exact event was already handled. */
        DUPLICATE
    }

    private static final int MAX_OPEN_ATTEMPTS = 3;

    private final IncidentRepository incidents;
    private final TimelineRepository timeline;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final Clock clock;
    private final MeterRegistry meters;

    public IncidentService(IncidentRepository incidents, TimelineRepository timeline, OutboxRepository outbox,
            ProcessedEventRepository processed, Clock clock, MeterRegistry meters) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.outbox = outbox;
        this.processed = processed;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Handles one alert exactly once. Everything (the processed marker, the incident, the timeline
     * and the outbox event) is written in one transaction, so a crash leaves either all of it or
     * none of it, and redelivery is safe.
     */
    @Transactional
    public AlertOutcome handleAlert(AlertReceived alert) {
        AlertOutcome outcome = doHandleAlert(alert);
        Counter.builder("pagerewind.incident.alerts.handled")
                .description("Alerts handled by the incident service")
                .tag("outcome", outcome.name().toLowerCase())
                .register(meters)
                .increment();
        return outcome;
    }

    private AlertOutcome doHandleAlert(AlertReceived alert) {
        Instant now = clock.instant();
        if (!processed.markProcessed(alert.eventId(), now)) {
            return AlertOutcome.DUPLICATE;
        }

        if (alert.status() == AlertStatus.RESOLVED) {
            Optional<UUID> open = incidents.lockOpenByFingerprint(alert.fingerprint());
            open.ifPresent(id -> timeline.add(id, TimelineEntry.Type.ALERT_RESOLVED,
                    "Alert resolved at source: " + alert.summary(), alert.source(), alertData(alert), now));
            return open.isPresent() ? AlertOutcome.RESOLVED_RECORDED : AlertOutcome.RESOLVED_IGNORED;
        }

        for (int attempt = 1; attempt <= MAX_OPEN_ATTEMPTS; attempt++) {
            Optional<UUID> created = incidents.insertIfNoneOpen(UUID.randomUUID(), alert.fingerprint(),
                    alert.service(), alert.severity(), alert.summary(), alert.labels(), now);
            if (created.isPresent()) {
                UUID id = created.get();
                timeline.add(id, TimelineEntry.Type.TRIGGERED, "Incident opened: " + alert.summary(),
                        alert.source(), Map.of("fingerprint", alert.fingerprint()), now);
                outbox.add(new IncidentTriggered(UUID.randomUUID(), now, IncidentTriggered.CURRENT_VERSION, id,
                        alert.fingerprint(), alert.service(), alert.severity(), alert.summary(), alert.labels()), now);
                attachAlert(id, alert, now);
                Counter.builder("pagerewind.incidents.opened")
                        .description("Incidents opened")
                        .tag("severity", alert.severity().name().toLowerCase())
                        .register(meters)
                        .increment();
                return AlertOutcome.CREATED;
            }
            // Someone else holds the open incident. Lock it; if it was resolved in between, try again.
            Optional<UUID> existing = incidents.lockOpenByFingerprint(alert.fingerprint());
            if (existing.isPresent()) {
                attachAlert(existing.get(), alert, now);
                return AlertOutcome.ATTACHED;
            }
        }
        throw new IllegalStateException("Could not open or find an incident for " + alert.fingerprint());
    }

    private void attachAlert(UUID incidentId, AlertReceived alert, Instant now) {
        incidents.addAlert(incidentId, now);
        timeline.add(incidentId, TimelineEntry.Type.ALERT, alert.summary(), alert.source(), alertData(alert), now);
    }

    private static Map<String, Object> alertData(AlertReceived alert) {
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", alert.eventId().toString());
        data.put("severity", alert.severity().name());
        data.put("repeat", alert.repeat());
        data.put("labels", alert.labels());
        data.put("startsAt", alert.startsAt().toString());
        return data;
    }

    @Transactional
    public Incident acknowledge(UUID id, String actor, String note) {
        return changeStatus(id, IncidentStatus.ACKNOWLEDGED, actor, note);
    }

    @Transactional
    public Incident resolve(UUID id, String actor, String note) {
        return changeStatus(id, IncidentStatus.RESOLVED, actor, note);
    }

    private Incident changeStatus(UUID id, IncidentStatus target, String actor, String note) {
        Instant now = clock.instant();
        Incident current = incidents.findById(id).orElseThrow(() -> new IncidentErrors.NotFound(id));
        if (!current.status().canMoveTo(target)) {
            throw new IncidentErrors.InvalidTransition(id, current.status(), target);
        }
        if (!incidents.changeStatus(id, current.version(), target, now)) {
            throw new IncidentErrors.ConcurrentChange(id);
        }

        Map<String, Object> data = new HashMap<>();
        if (note != null && !note.isBlank()) {
            data.put("note", note.trim());
        }
        PageRewindEvent event;
        TimelineEntry.Type type;
        String verb;
        if (target == IncidentStatus.ACKNOWLEDGED) {
            event = new IncidentAcknowledged(UUID.randomUUID(), now, IncidentAcknowledged.CURRENT_VERSION, id, actor);
            type = TimelineEntry.Type.ACKNOWLEDGED;
            verb = "Acknowledged";
        } else {
            event = new IncidentResolved(UUID.randomUUID(), now, IncidentResolved.CURRENT_VERSION, id, actor);
            type = TimelineEntry.Type.RESOLVED;
            verb = "Resolved";
        }
        timeline.add(id, type, verb + " by " + actor, actor, data, now);
        outbox.add(event, now);
        return incidents.findById(id).orElseThrow(() -> new IncidentErrors.NotFound(id));
    }

    @Transactional
    public void addNote(UUID id, String actor, String text) {
        incidents.findById(id).orElseThrow(() -> new IncidentErrors.NotFound(id));
        timeline.add(id, TimelineEntry.Type.NOTE, text.trim(), actor, Map.of(), clock.instant());
    }

    /**
     * Adds the flight recorder's findings to the timeline, once per event. Evidence for an incident that
     * does not exist (deleted, or another installation) is ignored.
     */
    @Transactional
    public void recordEvidence(EvidenceCaptured evidence) {
        Instant now = clock.instant();
        if (!processed.markProcessed(evidence.eventId(), now) || incidents.findById(evidence.incidentId()).isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("containers", evidence.containers());
        data.put("logLines", evidence.logLines());
        data.put("changes", evidence.changes());
        data.put("bundleBytes", evidence.bundleBytes());
        if (evidence.likelyCause() != null) {
            data.put("likelyCause", evidence.likelyCause());
        }
        String message = "Evidence captured: %d log lines from %d containers, %d changes in the hour before"
                .formatted(evidence.logLines(), evidence.containers(), evidence.changes());
        timeline.add(evidence.incidentId(), TimelineEntry.Type.EVIDENCE, message, "capture", data, evidence.occurredAt());
    }

    @Transactional(readOnly = true)
    public Incident get(UUID id) {
        return incidents.findById(id).orElseThrow(() -> new IncidentErrors.NotFound(id));
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID id) {
        return timeline.findByIncident(id);
    }

    @Transactional(readOnly = true)
    public List<Incident> list(IncidentStatus status, int limit) {
        return incidents.find(status, limit);
    }
}

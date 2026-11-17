package dev.pagerewind.schedule;

import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.PageRequested;
import dev.pagerewind.messaging.OutboxRepository;
import dev.pagerewind.messaging.ProcessedEventRepository;
import dev.pagerewind.schedule.DirectoryRepository.Person;
import dev.pagerewind.schedule.EscalationRepository.Escalation;
import dev.pagerewind.schedule.EscalationRepository.Job;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts escalations when incidents open, stops them when someone responds, and fires due steps.
 *
 * <p>Timers are rows in {@code escalation_job}. Firing a step writes a {@link PageRequested} to the
 * outbox in the same transaction that marks the job fired, so a step is paged exactly once even if
 * the service restarts at any point.
 */
@Service
public class EscalationService {

    public enum Outcome { STARTED, UNROUTED, NO_POLICY, ALREADY_KNOWN, STOPPED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final DirectoryRepository directory;
    private final EscalationRepository escalations;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final TransactionTemplate transaction;
    private final ScheduleProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public EscalationService(DirectoryRepository directory, EscalationRepository escalations, OutboxRepository outbox,
            ProcessedEventRepository processed, TransactionTemplate transaction, ScheduleProperties properties,
            Clock clock, MeterRegistry meters) {
        this.directory = directory;
        this.escalations = escalations;
        this.outbox = outbox;
        this.processed = processed;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public Outcome onTriggered(IncidentTriggered event) {
        Instant now = clock.instant();
        if (!processed.markProcessed(event.eventId(), now)) {
            return count(Outcome.DUPLICATE);
        }
        Optional<String> team = directory.teamForService(event.service());
        if (team.isEmpty()) {
            log.warn("No team handles service {} and there is no default team; incident {} will not page anyone",
                    event.service(), event.incidentId());
            escalations.stop(event.incidentId(), "NO_ROUTE", now);
            return count(Outcome.UNROUTED);
        }
        List<EscalationStep> steps = directory.steps(team.get());
        if (steps.isEmpty()) {
            log.warn("Team {} has no escalation policy; incident {} will not page anyone", team.get(), event.incidentId());
            escalations.stop(event.incidentId(), "NO_POLICY", now);
            return count(Outcome.NO_POLICY);
        }
        if (!escalations.insertActive(event.incidentId(), team.get(), event.service(), event.severity(), event.summary(), now)) {
            return count(Outcome.ALREADY_KNOWN);
        }
        int repeat = directory.findTeam(team.get()).map(DirectoryRepository.Team::policyRepeat).orElse(0);
        for (EscalationPlan.PlannedJob job : EscalationPlan.plan(steps, repeat, event.occurredAt())) {
            escalations.addJob(event.incidentId(), job);
        }
        return count(Outcome.STARTED);
    }

    /** Called for acknowledge and resolve: nobody else needs to be paged. */
    @Transactional
    public Outcome onStopped(UUID eventId, UUID incidentId, String reason) {
        Instant now = clock.instant();
        if (!processed.markProcessed(eventId, now)) {
            return count(Outcome.DUPLICATE);
        }
        // Jobs first, then the escalation row: the same lock order as fireDue, so the two never deadlock.
        escalations.cancelPending(incidentId);
        escalations.stop(incidentId, reason, now);
        return count(Outcome.STOPPED);
    }

    /** Fires every due step, one batch at a time. Returns how many jobs were handled. */
    public int fireDue() {
        int total = 0;
        int handled;
        do {
            Integer batch = transaction.execute(status -> fireBatch());
            handled = batch == null ? 0 : batch;
            total += handled;
        } while (handled == properties.escalationBatchSize());
        return total;
    }

    private int fireBatch() {
        Instant now = clock.instant();
        List<Job> due = escalations.lockDue(now, properties.escalationBatchSize());
        for (Job job : due) {
            fire(job, now);
        }
        return due.size();
    }

    private void fire(Job job, Instant now) {
        Optional<String> status = escalations.lockStatus(job.incidentId());
        if (status.isEmpty() || !status.get().equals("ACTIVE")) {
            escalations.finishJob(job.id(), "CANCELLED", null, "escalation stopped", now);
            return;
        }
        Optional<String> personId = switch (job.targetType()) {
            case PERSON -> Optional.of(job.targetId());
            case ROTATION -> directory.findRotation(job.targetId())
                    .flatMap(r -> OnCall.at(r, directory.overrides(r.id(), now), now))
                    .map(OnCall.Shift::personId);
        };
        if (personId.isEmpty()) {
            skip(job, null, "nobody is on call in rotation " + job.targetId(), now);
            return;
        }
        Optional<Person> person = directory.findPerson(personId.get());
        if (person.isEmpty() || person.get().contacts().isEmpty()) {
            skip(job, personId.get(), "person " + personId.get() + " has no contact details", now);
            return;
        }
        Escalation escalation = escalations.find(job.incidentId()).orElseThrow();
        outbox.add(new PageRequested(UUID.randomUUID(), now, PageRequested.CURRENT_VERSION, job.incidentId(),
                job.stepNo(), job.round(), person.get().id(), person.get().name(), person.get().contacts(),
                escalation.service(), escalation.severity(), escalation.summary()), now);
        escalations.finishJob(job.id(), "FIRED", person.get().id(), null, now);
        Counter.builder("pagerewind.escalation.steps.fired").description("Escalation steps that paged someone")
                .register(meters).increment();
    }

    private void skip(Job job, String personId, String note, Instant now) {
        log.warn("Escalation step {} round {} of incident {} skipped: {}", job.stepNo(), job.round(), job.incidentId(), note);
        escalations.finishJob(job.id(), "SKIPPED", personId, note, now);
        Counter.builder("pagerewind.escalation.steps.skipped").description("Escalation steps with nobody to page")
                .register(meters).increment();
    }

    private Outcome count(Outcome outcome) {
        Counter.builder("pagerewind.escalation.events").description("Incident events handled by the schedule service")
                .tag("outcome", outcome.name().toLowerCase())
                .register(meters).increment();
        return outcome;
    }
}

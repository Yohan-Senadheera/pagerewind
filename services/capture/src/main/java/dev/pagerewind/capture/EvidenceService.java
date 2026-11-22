package dev.pagerewind.capture;

import dev.pagerewind.capture.ChangeRanking.Ranked;
import dev.pagerewind.capture.ChangeRanking.Target;
import dev.pagerewind.capture.LogBuffer.ContainerKey;
import dev.pagerewind.capture.LogBuffer.LogLine;
import dev.pagerewind.events.EvidenceCaptured;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.messaging.OutboxRepository;
import dev.pagerewind.messaging.ProcessedEventRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When an incident opens, freezes what the recorder knows about the alerting workload into one bundle,
 * stores it, and tells the incident service (through the outbox) what was found.
 */
@Service
class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    record StoredEvidence(UUID incidentId, Instant capturedAt, byte[] bundle) {
    }

    private final LogBuffer buffer;
    private final ChangeRepository changes;
    private final ChangeRanking ranking;
    private final CaptureProperties properties;
    private final JdbcClient jdbc;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final TransactionTemplate transaction;
    private final Clock clock;

    EvidenceService(LogBuffer buffer, ChangeRepository changes, ChangeRanking ranking, CaptureProperties properties,
            JdbcClient jdbc, OutboxRepository outbox, ProcessedEventRepository processed, TransactionTemplate transaction,
            Clock clock) {
        this.buffer = buffer;
        this.changes = changes;
        this.ranking = ranking;
        this.properties = properties;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.processed = processed;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Handles one IncidentTriggered exactly once: bundle, processed marker and outbox event in one transaction. */
    void capture(IncidentTriggered incident) {
        Target target = Target.fromLabels(incident.labels());
        String pod = incident.labels().get("pod");
        Instant at = incident.occurredAt();
        // Snapshot and rank outside the transaction: only memory and a read.
        Map<ContainerKey, List<LogLine>> logs = buffer.snapshot(key -> EvidenceBundle.matches(key, target, pod));
        List<Ranked> ranked = ranking.rank(changes.between(at.minus(properties.lookBack()), at), target, at);
        EvidenceBundle.Result bundle = EvidenceBundle.build(incident.incidentId(), at, target, logs, ranked,
                properties.maxBundleSize().toBytes());

        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (!processed.markProcessed(incident.eventId(), now)) {
                return;
            }
            jdbc.sql("""
                    INSERT INTO evidence (incident_id, captured_at, containers, log_lines, changes, likely_cause, bundle)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (incident_id) DO NOTHING""")
                    .params(incident.incidentId(), Timestamp.from(now), bundle.containers(), bundle.logLines(),
                            bundle.changes(), bundle.likelyCause(), bundle.zip())
                    .update();
            outbox.add(new EvidenceCaptured(UUID.randomUUID(), now, EvidenceCaptured.CURRENT_VERSION, incident.incidentId(),
                    bundle.containers(), bundle.logLines(), bundle.changes(), bundle.zip().length, bundle.likelyCause()), now);
        });
        log.info("Evidence for incident {}: {} lines from {} containers, {} changes, {} bytes{}", incident.incidentId(),
                bundle.logLines(), bundle.containers(), bundle.changes(), bundle.zip().length,
                bundle.likelyCause() == null ? "" : "; likely cause: " + bundle.likelyCause());
    }

    Optional<StoredEvidence> find(UUID incidentId) {
        return jdbc.sql("SELECT incident_id, captured_at, bundle FROM evidence WHERE incident_id = ?").param(incidentId)
                .query((rs, row) -> new StoredEvidence(rs.getObject("incident_id", UUID.class),
                        rs.getTimestamp("captured_at").toInstant(), rs.getBytes("bundle")))
                .optional();
    }

    int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM evidence WHERE captured_at < ?").param(Timestamp.from(cutoff)).update();
    }
}

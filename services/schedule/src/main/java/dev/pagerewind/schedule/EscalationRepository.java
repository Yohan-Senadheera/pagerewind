package dev.pagerewind.schedule;

import dev.pagerewind.events.Severity;
import dev.pagerewind.messaging.Db;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Running escalations and their timers. */
@Repository
public class EscalationRepository {

    public record Escalation(UUID incidentId, String teamId, String service, Severity severity, String summary,
            String status, String stopReason) {

        boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record Job(long id, UUID incidentId, int stepNo, int round, EscalationStep.TargetType targetType,
            String targetId, Instant fireAt, String status, String personId, String note) {
    }

    private final JdbcClient jdbc;

    public EscalationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the row was created, false if this incident was already known */
    public boolean insertActive(UUID incidentId, String teamId, String service, Severity severity, String summary, Instant now) {
        return jdbc.sql("""
                        INSERT INTO incident_escalation (incident_id, team_id, service, severity, summary, status, created_at)
                        VALUES (:id, :team, :service, :severity, :summary, 'ACTIVE', :now)
                        ON CONFLICT (incident_id) DO NOTHING""")
                .param("id", incidentId).param("team", teamId).param("service", service)
                .param("severity", severity.name()).param("summary", summary).param("now", Db.toDb(now))
                .update() == 1;
    }

    /**
     * Marks an escalation as stopped. If the incident is not known yet (the stop event arrived first),
     * a stopped placeholder is stored so a late trigger event starts nothing.
     */
    public void stop(UUID incidentId, String reason, Instant now) {
        jdbc.sql("""
                        INSERT INTO incident_escalation (incident_id, status, stop_reason, created_at, stopped_at)
                        VALUES (:id, 'STOPPED', :reason, :now, :now)
                        ON CONFLICT (incident_id) DO UPDATE
                            SET status = 'STOPPED',
                                stop_reason = COALESCE(incident_escalation.stop_reason, EXCLUDED.stop_reason),
                                stopped_at = COALESCE(incident_escalation.stopped_at, EXCLUDED.stopped_at)""")
                .param("id", incidentId).param("reason", reason).param("now", Db.toDb(now)).update();
    }

    public Optional<Escalation> find(UUID incidentId) {
        return jdbc.sql("""
                        SELECT incident_id, team_id, service, severity, summary, status, stop_reason
                          FROM incident_escalation WHERE incident_id = :id""")
                .param("id", incidentId)
                .query((rs, row) -> new Escalation(rs.getObject("incident_id", UUID.class), rs.getString("team_id"),
                        rs.getString("service"),
                        rs.getString("severity") == null ? null : Severity.valueOf(rs.getString("severity")),
                        rs.getString("summary"), rs.getString("status"), rs.getString("stop_reason")))
                .optional();
    }

    /** Reads the escalation and holds a share lock so it cannot be stopped until this transaction ends. */
    public Optional<String> lockStatus(UUID incidentId) {
        return jdbc.sql("SELECT status FROM incident_escalation WHERE incident_id = :id FOR SHARE")
                .param("id", incidentId).query(String.class).optional();
    }

    public void addJob(UUID incidentId, EscalationPlan.PlannedJob job) {
        jdbc.sql("""
                        INSERT INTO escalation_job (incident_id, step_no, round, target_type, target_id, fire_at, status)
                        VALUES (:id, :step, :round, :type, :target, :fireAt, 'PENDING')""")
                .param("id", incidentId).param("step", job.stepNo()).param("round", job.round())
                .param("type", job.targetType().name()).param("target", job.targetId())
                .param("fireAt", Db.toDb(job.fireAt())).update();
    }

    public int cancelPending(UUID incidentId) {
        return jdbc.sql("UPDATE escalation_job SET status = 'CANCELLED' WHERE incident_id = :id AND status = 'PENDING'")
                .param("id", incidentId).update();
    }

    /** Due jobs, locked for this transaction. Other runners skip them instead of waiting. */
    public List<Job> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT * FROM escalation_job
                         WHERE status = 'PENDING' AND fire_at <= :now
                         ORDER BY fire_at, id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED""")
                .param("now", Db.toDb(now)).param("limit", limit)
                .query((rs, row) -> mapJob(rs))
                .list();
    }

    public List<Job> jobs(UUID incidentId) {
        return jdbc.sql("SELECT * FROM escalation_job WHERE incident_id = :id ORDER BY round, step_no")
                .param("id", incidentId).query((rs, row) -> mapJob(rs)).list();
    }

    public void finishJob(long id, String status, String personId, String note, Instant now) {
        jdbc.sql("UPDATE escalation_job SET status = :status, person_id = :person, note = :note, fired_at = :now WHERE id = :id")
                .param("status", status).param("person", personId).param("note", note)
                .param("now", Db.toDb(now)).param("id", id).update();
    }

    private static Job mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getLong("id"), rs.getObject("incident_id", UUID.class), rs.getInt("step_no"), rs.getInt("round"),
                EscalationStep.TargetType.valueOf(rs.getString("target_type")), rs.getString("target_id"),
                Db.instant(rs, "fire_at"), rs.getString("status"), rs.getString("person_id"), rs.getString("note"));
    }
}

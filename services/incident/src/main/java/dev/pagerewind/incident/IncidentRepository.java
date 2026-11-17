package dev.pagerewind.incident;

import dev.pagerewind.events.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class IncidentRepository {

    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, fingerprint, service, severity, summary, status, alert_count, labels::text AS labels,
            created_at, updated_at, acknowledged_at, resolved_at, version""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IncidentRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Creates a TRIGGERED incident unless an open one with the same fingerprint already exists.
     *
     * @return the new incident id, or empty if an open incident already exists
     */
    public Optional<UUID> insertIfNoneOpen(UUID id, String fingerprint, String service, Severity severity,
            String summary, Map<String, String> labels, Instant now) {
        return jdbc.sql("""
                        INSERT INTO incident (id, fingerprint, service, severity, summary, status, alert_count,
                                              labels, created_at, updated_at, version)
                        VALUES (:id, :fingerprint, :service, :severity, :summary, 'TRIGGERED', 0,
                                CAST(:labels AS jsonb), :now, :now, 0)
                        ON CONFLICT (fingerprint) WHERE status <> 'RESOLVED' DO NOTHING
                        RETURNING id""")
                .param("id", id)
                .param("fingerprint", fingerprint)
                .param("service", service)
                .param("severity", severity.name())
                .param("summary", summary)
                .param("labels", json.writeValueAsString(labels))
                .param("now", Db.toDb(now))
                .query(UUID.class)
                .optional();
    }

    /** Finds the open incident for a fingerprint and locks its row until the transaction ends. */
    public Optional<UUID> lockOpenByFingerprint(String fingerprint) {
        return jdbc.sql("SELECT id FROM incident WHERE fingerprint = :fingerprint AND status <> 'RESOLVED' FOR UPDATE")
                .param("fingerprint", fingerprint)
                .query(UUID.class)
                .optional();
    }

    public void addAlert(UUID id, Instant now) {
        jdbc.sql("UPDATE incident SET alert_count = alert_count + 1, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("now", Db.toDb(now))
                .update();
    }

    public Optional<Incident> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Incident> find(IncidentStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM incident ORDER BY created_at DESC, id LIMIT :limit")
                    .param("limit", limit)
                    .query(this::map)
                    .list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM incident WHERE status = :status ORDER BY created_at DESC, id LIMIT :limit")
                .param("status", status.name())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * Moves an incident to a new status if nobody changed it since {@code expectedVersion} was read
     * (optimistic locking).
     *
     * @return true if the row was updated, false if the version did not match
     */
    public boolean changeStatus(UUID id, long expectedVersion, IncidentStatus target, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE incident
                           SET status = :status,
                               version = version + 1,
                               updated_at = :now,
                               acknowledged_at = COALESCE(CAST(:acknowledgedAt AS timestamptz), acknowledged_at),
                               resolved_at = COALESCE(CAST(:resolvedAt AS timestamptz), resolved_at)
                         WHERE id = :id AND version = :version""")
                .param("status", target.name())
                .param("now", Db.toDb(now))
                .param("acknowledgedAt", target == IncidentStatus.ACKNOWLEDGED ? Db.toDb(now) : null)
                .param("resolvedAt", target == IncidentStatus.RESOLVED ? Db.toDb(now) : null)
                .param("id", id)
                .param("version", expectedVersion)
                .update();
        return rows == 1;
    }

    private Incident map(ResultSet rs, int row) throws SQLException {
        return new Incident(
                rs.getObject("id", UUID.class),
                rs.getString("fingerprint"),
                rs.getString("service"),
                Severity.valueOf(rs.getString("severity")),
                rs.getString("summary"),
                IncidentStatus.valueOf(rs.getString("status")),
                rs.getInt("alert_count"),
                json.readValue(rs.getString("labels"), LABELS),
                Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"),
                Db.instant(rs, "acknowledged_at"),
                Db.instant(rs, "resolved_at"),
                rs.getLong("version"));
    }
}

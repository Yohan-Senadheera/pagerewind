package dev.pagerewind.status;

import dev.pagerewind.events.Severity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keeps the read model. Events can arrive out of order, so the status only ever moves forward
 * (TRIGGERED, then ACKNOWLEDGED, then RESOLVED) and details are filled in whenever they arrive.
 */
@Repository
public class StatusRepository {

    private final JdbcClient jdbc;

    public StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime db(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public boolean markProcessed(UUID eventId, Instant now) {
        return jdbc.sql("INSERT INTO processed_event (event_id, processed_at) VALUES (:id, :now) ON CONFLICT DO NOTHING")
                .param("id", eventId).param("now", db(now)).update() == 1;
    }

    public void triggered(UUID incidentId, String service, Severity severity, Instant openedAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO status_incident (incident_id, service, severity, status, opened_at, updated_at)
                        VALUES (:id, :service, :severity, 'TRIGGERED', :opened, :now)
                        ON CONFLICT (incident_id) DO UPDATE
                            SET service = EXCLUDED.service, severity = EXCLUDED.severity,
                                opened_at = EXCLUDED.opened_at, updated_at = EXCLUDED.updated_at""")
                .param("id", incidentId).param("service", service).param("severity", severity.name())
                .param("opened", db(openedAt)).param("now", db(now)).update();
    }

    public void acknowledged(UUID incidentId, Instant now) {
        jdbc.sql("""
                        INSERT INTO status_incident (incident_id, status, updated_at) VALUES (:id, 'ACKNOWLEDGED', :now)
                        ON CONFLICT (incident_id) DO UPDATE
                            SET status = CASE WHEN status_incident.status = 'RESOLVED' THEN 'RESOLVED' ELSE 'ACKNOWLEDGED' END,
                                updated_at = EXCLUDED.updated_at""")
                .param("id", incidentId).param("now", db(now)).update();
    }

    public void resolved(UUID incidentId, Instant resolvedAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO status_incident (incident_id, status, resolved_at, updated_at)
                        VALUES (:id, 'RESOLVED', :resolved, :now)
                        ON CONFLICT (incident_id) DO UPDATE
                            SET status = 'RESOLVED', resolved_at = EXCLUDED.resolved_at, updated_at = EXCLUDED.updated_at""")
                .param("id", incidentId).param("resolved", db(resolvedAt)).param("now", db(now)).update();
    }

    /** Open incidents whose service is known. */
    public List<ServiceStatus.OpenIncident> openIncidents() {
        return jdbc.sql("""
                        SELECT service, severity, opened_at FROM status_incident
                         WHERE status <> 'RESOLVED' AND service IS NOT NULL""")
                .query((rs, row) -> {
                    OffsetDateTime opened = rs.getObject("opened_at", OffsetDateTime.class);
                    return new ServiceStatus.OpenIncident(rs.getString("service"), Severity.valueOf(rs.getString("severity")),
                            opened == null ? null : opened.toInstant());
                })
                .list();
    }

    /** Every service that ever had an incident. */
    public List<String> knownServices() {
        return jdbc.sql("SELECT DISTINCT service FROM status_incident WHERE service IS NOT NULL ORDER BY service")
                .query(String.class).list();
    }
}

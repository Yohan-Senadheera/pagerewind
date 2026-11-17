package dev.pagerewind.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.pagerewind.messaging.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TimelineRepository {

    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TimelineRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void add(UUID incidentId, TimelineEntry.Type type, String message, String actor,
            Map<String, ?> data, Instant at) {
        jdbc.sql("""
                        INSERT INTO incident_event (incident_id, type, message, actor, data, occurred_at)
                        VALUES (:incidentId, :type, :message, :actor, CAST(:data AS jsonb), :at)""")
                .param("incidentId", incidentId)
                .param("type", type.name())
                .param("message", message)
                .param("actor", actor)
                .param("data", json.writeValueAsString(data == null ? Map.of() : data))
                .param("at", Db.toDb(at))
                .update();
    }

    public List<TimelineEntry> findByIncident(UUID incidentId) {
        return jdbc.sql("""
                        SELECT id, type, message, actor, data::text AS data, occurred_at
                          FROM incident_event WHERE incident_id = :incidentId ORDER BY id""")
                .param("incidentId", incidentId)
                .query(this::map)
                .list();
    }

    private TimelineEntry map(ResultSet rs, int row) throws SQLException {
        return new TimelineEntry(
                rs.getLong("id"),
                TimelineEntry.Type.valueOf(rs.getString("type")),
                rs.getString("message"),
                rs.getString("actor"),
                json.readValue(rs.getString("data"), DATA),
                Db.instant(rs, "occurred_at"));
    }
}

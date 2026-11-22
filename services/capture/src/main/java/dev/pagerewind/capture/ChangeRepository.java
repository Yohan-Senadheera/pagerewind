package dev.pagerewind.capture;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ChangeRepository {

    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ChangeRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(ChangeEvent change) {
        jdbc.sql("""
                INSERT INTO change_event (id, occurred_at, kind, namespace, name, workload, summary, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))""")
                .params(change.id(), Timestamp.from(change.occurredAt()), change.kind().name(), change.namespace(),
                        change.name(), change.workload(), change.summary(), json.writeValueAsString(change.details()))
                .update();
    }

    /** Changes in [from, to], newest first. */
    List<ChangeEvent> between(Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, occurred_at, kind, namespace, name, workload, summary, details::text AS details
                FROM change_event WHERE occurred_at BETWEEN ? AND ? ORDER BY occurred_at DESC LIMIT 1000""")
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, row) -> new ChangeEvent(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        ChangeEvent.Kind.valueOf(rs.getString("kind")), rs.getString("namespace"), rs.getString("name"),
                        rs.getString("workload"), rs.getString("summary"), json.readValue(rs.getString("details"), DETAILS)))
                .list();
    }

    int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM change_event WHERE occurred_at < ?").param(Timestamp.from(cutoff)).update();
    }
}

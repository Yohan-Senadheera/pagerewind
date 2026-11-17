package dev.pagerewind.messaging;

import dev.pagerewind.events.PageRewindEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code outbox} table. Each service creates the table in its own schema (see the incident
 * service migration for the shape).
 */
public class OutboxRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Stores an event for publishing. Must run in the same transaction as the change it describes. */
    public void add(PageRewindEvent event, Instant now) {
        jdbc.sql("""
                        INSERT INTO outbox (event_id, event_type, routing_key, payload, created_at)
                        VALUES (:eventId, :eventType, :routingKey, CAST(:payload AS jsonb), :now)""")
                .param("eventId", event.eventId())
                .param("eventType", event.type().name())
                .param("routingKey", event.type().routingKey())
                .param("payload", json.writeValueAsString(event))
                .param("now", Db.toDb(now))
                .update();
    }

    /**
     * Locks the oldest unpublished rows. {@code SKIP LOCKED} lets several relays run at once
     * without publishing the same row twice.
     */
    public List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, event_id, event_type, routing_key, payload::text AS payload
                          FROM outbox
                         WHERE published_at IS NULL
                         ORDER BY id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, row) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("routing_key"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(long id, Instant now) {
        jdbc.sql("UPDATE outbox SET published_at = :now, attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("now", Db.toDb(now))
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String error) {
        String trimmed = error == null ? "unknown" : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", trimmed)
                .param("id", id)
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}

package dev.pagerewind.messaging;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code processed_event} table that makes consumers idempotent. */
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that an event is being handled. Call this inside the same transaction as the work.
     *
     * @return true the first time, false if the event was already handled
     */
    public boolean markProcessed(UUID eventId, Instant now) {
        return jdbc.sql("INSERT INTO processed_event (event_id, processed_at) VALUES (:id, :now) ON CONFLICT DO NOTHING")
                .param("id", eventId)
                .param("now", Db.toDb(now))
                .update() == 1;
    }
}

package dev.pagerewind.notify;

import dev.pagerewind.events.Contact;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {

    public record Delivery(long id, UUID eventId, UUID incidentId, String personId, Contact.Channel channel,
            String address, String status, int attempts) {
    }

    private final JdbcClient jdbc;

    public DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime db(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Records that this page should go to this contact. Does nothing if the row already exists
     * (the event was delivered before).
     */
    public void insertIfAbsent(UUID eventId, UUID incidentId, String personId, Contact contact, int step, String status,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO delivery (event_id, incident_id, person_id, channel, address, step, status, created_at)
                        VALUES (:event, :incident, :person, :channel, :address, :step, :status, :now)
                        ON CONFLICT (event_id, channel, address) DO NOTHING""")
                .param("event", eventId).param("incident", incidentId).param("person", personId)
                .param("channel", contact.channel().name()).param("address", contact.address())
                .param("step", step).param("status", status).param("now", db(now))
                .update();
    }

    public boolean exists(UUID eventId, Contact contact) {
        return jdbc.sql("SELECT count(*) FROM delivery WHERE event_id = :event AND channel = :channel AND address = :address")
                .param("event", eventId).param("channel", contact.channel().name()).param("address", contact.address())
                .query(Long.class).single() > 0;
    }

    /** True if this person was already paged (or is being paged) for this incident on this channel recently. */
    public boolean pagedRecently(UUID incidentId, String personId, Contact.Channel channel, UUID exceptEvent,
            Instant now, Duration window) {
        return jdbc.sql("""
                        SELECT count(*) FROM delivery
                         WHERE incident_id = :incident AND person_id = :person AND channel = :channel
                           AND event_id <> :event AND status IN ('PENDING', 'SENT') AND created_at > :since""")
                .param("incident", incidentId).param("person", personId).param("channel", channel.name())
                .param("event", exceptEvent).param("since", db(now.minus(window)))
                .query(Long.class).single() > 0;
    }

    public List<Delivery> forEvent(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, event_id, incident_id, person_id, channel, address, status, attempts
                          FROM delivery WHERE event_id = :event ORDER BY id""")
                .param("event", eventId)
                .query((rs, row) -> new Delivery(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getObject("incident_id", UUID.class), rs.getString("person_id"),
                        Contact.Channel.valueOf(rs.getString("channel")), rs.getString("address"),
                        rs.getString("status"), rs.getInt("attempts")))
                .list();
    }

    /**
     * Claims a pending row for sending. Only one consumer can claim it; a claim older than
     * {@code lease} is treated as abandoned (the sender crashed) and can be taken again.
     */
    public boolean claim(long id, Instant now, Duration lease) {
        return jdbc.sql("""
                        UPDATE delivery SET claimed_at = :now
                         WHERE id = :id AND status = 'PENDING' AND (claimed_at IS NULL OR claimed_at < :expired)""")
                .param("now", db(now)).param("id", id).param("expired", db(now.minus(lease)))
                .update() == 1;
    }

    public void finish(long id, boolean sent, int attempts, String error, Instant now) {
        jdbc.sql("""
                        UPDATE delivery SET status = :status, attempts = attempts + :attempts, last_error = :error,
                               sent_at = CASE WHEN :sent THEN CAST(:now AS timestamptz) ELSE sent_at END
                         WHERE id = :id""")
                .param("status", sent ? "SENT" : "FAILED").param("attempts", attempts)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("sent", sent).param("now", db(now)).param("id", id)
                .update();
    }

    /** The person behind a Telegram chat that was successfully paged for this incident. */
    public Optional<String> personPagedInChat(UUID incidentId, String chatId) {
        return jdbc.sql("""
                        SELECT person_id FROM delivery
                         WHERE incident_id = :incident AND channel = 'TELEGRAM' AND address = :chat AND status = 'SENT'
                         ORDER BY id DESC LIMIT 1""")
                .param("incident", incidentId).param("chat", chatId)
                .query(String.class).optional();
    }

    /** @return true the first time the nonce is used */
    public boolean useNonce(String nonce, Instant now) {
        return jdbc.sql("INSERT INTO action_token_use (nonce, used_at) VALUES (:nonce, :now) ON CONFLICT DO NOTHING")
                .param("nonce", nonce).param("now", db(now)).update() == 1;
    }
}

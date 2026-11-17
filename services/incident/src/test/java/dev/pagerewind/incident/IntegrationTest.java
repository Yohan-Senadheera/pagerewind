package dev.pagerewind.incident;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for tests against real PostgreSQL and RabbitMQ. All subclasses share one Spring context
 * and one set of containers. The background outbox relay is off so tests drive it directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pagerewind.messaging.outbox.relay-enabled=false")
@Import(TestcontainersConfiguration.class)
abstract class IntegrationTest {

    @Autowired
    protected IncidentService service;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected AmqpAdmin admin;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected MeterRegistry meters;
    @Autowired
    protected IncidentProperties properties;

    @BeforeEach
    void cleanState() {
        jdbc.sql("TRUNCATE incident, incident_event, outbox, processed_event").update();
        admin.purgeQueue(properties.alertsQueue(), false);
        admin.purgeQueue(properties.alertsDeadLetterQueue(), false);
    }

    protected static AlertReceived alert(String fingerprint) {
        return alert(fingerprint, AlertStatus.FIRING);
    }

    protected static AlertReceived alert(String fingerprint, AlertStatus status) {
        Instant now = Instant.now();
        return new AlertReceived(UUID.randomUUID(), now, AlertReceived.CURRENT_VERSION, "test", "checkout",
                Severity.CRITICAL, status, "Checkout 5xx above 5%", Map.of("alertname", "HighErrorRate"),
                fingerprint, false, now);
    }

    protected static String newFingerprint() {
        return "test:" + UUID.randomUUID();
    }

    protected long count(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    protected double counter(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}

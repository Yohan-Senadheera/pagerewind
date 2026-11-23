package dev.pagerewind.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.events.IncidentAcknowledged;
import dev.pagerewind.events.IncidentResolved;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.events.PageRewindEvent;
import dev.pagerewind.events.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pagerewind.status.title=Acme status",
        "pagerewind.status.services=website, api"})
@Import(TestcontainersConfiguration.class)
class StatusIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE status_incident, processed_event").update();
    }

    @Test
    void configuredServicesStartOperational() throws Exception {
        Map<String, Object> status = status();
        assertThat(status).containsEntry("overall", "OPERATIONAL").containsEntry("title", "Acme status");
        assertThat(levels(status)).containsExactly(Map.entry("api", "OPERATIONAL"), Map.entry("website", "OPERATIONAL"));
    }

    @Test
    void incidentLifecycleChangesTheServiceStatus() throws Exception {
        UUID incident = UUID.randomUUID();
        publish(triggered(incident, "api", Severity.CRITICAL));
        await().atMost(WAIT).untilAsserted(() -> assertThat(levels(status())).contains(Map.entry("api", "MAJOR_OUTAGE")));
        assertThat(status()).containsEntry("overall", "MAJOR_OUTAGE");

        publish(new IncidentAcknowledged(UUID.randomUUID(), Instant.now(), 1, incident, "alice"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(count("ACKNOWLEDGED")).isEqualTo(1));
        assertThat(levels(status())).as("acknowledged is not fixed").contains(Map.entry("api", "MAJOR_OUTAGE"));

        publish(new IncidentResolved(UUID.randomUUID(), Instant.now(), 1, incident, "alice"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(levels(status())).contains(Map.entry("api", "OPERATIONAL")));
        assertThat(status()).containsEntry("overall", "OPERATIONAL");
    }

    @Test
    void warningIncidentOnANewServiceShowsDegraded() throws Exception {
        publish(triggered(UUID.randomUUID(), "search", Severity.WARNING));
        await().atMost(WAIT).untilAsserted(() -> assertThat(levels(status())).contains(Map.entry("search", "DEGRADED")));
        assertThat(status()).containsEntry("overall", "DEGRADED");
    }

    @Test
    void resolveArrivingBeforeTriggerIsNotUndone() throws Exception {
        UUID incident = UUID.randomUUID();
        publish(new IncidentResolved(UUID.randomUUID(), Instant.now(), 1, incident, "alice"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(count("RESOLVED")).isEqualTo(1));
        publish(new IncidentAcknowledged(UUID.randomUUID(), Instant.now(), 1, incident, "alice"));
        publish(triggered(incident, "api", Severity.CRITICAL));
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT service FROM status_incident WHERE incident_id = ?").param(incident).query(String.class).single())
                .isEqualTo("api"));
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> assertThat(count("RESOLVED")).isEqualTo(1));

        assertThat(levels(status())).contains(Map.entry("api", "OPERATIONAL"));
    }

    @Test
    void duplicateEventsAreCountedOnce() throws Exception {
        IncidentTriggered event = triggered(UUID.randomUUID(), "api", Severity.WARNING);
        publish(event);
        publish(event);
        IncidentTriggered second = triggered(UUID.randomUUID(), "api", Severity.WARNING);
        publish(second);
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM processed_event").query(Long.class).single()).isEqualTo(2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) status().get("services");
        assertThat(services).filteredOn(s -> "api".equals(s.get("service"))).singleElement()
                .satisfies(s -> assertThat(s.get("openIncidents")).isEqualTo(2));
    }

    @Test
    void htmlPageShowsServicesButNeverAlertText() throws Exception {
        publish(triggered(UUID.randomUUID(), "api", Severity.CRITICAL));
        await().atMost(WAIT).untilAsserted(() -> assertThat(levels(status())).contains(Map.entry("api", "MAJOR_OUTAGE")));

        HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
        assertThat(page.body()).contains("<title>Acme status</title>").contains("Major outage").contains(">api<")
                .contains(">website<").doesNotContain("db-primary-7").doesNotContain("password");
    }

    @Test
    void pastIncidentsAreListedWithImpactAndDurationButNoAlertText() throws Exception {
        UUID resolved = UUID.randomUUID();
        publish(triggered(resolved, "api", Severity.CRITICAL));
        publish(new IncidentResolved(UUID.randomUUID(), Instant.now(), 1, resolved, "alice"));
        UUID open = UUID.randomUUID();
        publish(triggered(open, "website", Severity.WARNING));
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM status_incident").query(Long.class).single()).isEqualTo(2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) status().get("history");
        assertThat(history).hasSize(2);
        assertThat(history).anySatisfy(h -> {
            assertThat(h).containsEntry("service", "api").containsEntry("impact", "MAJOR_OUTAGE");
            assertThat(h.get("resolvedAt")).isNotNull();
            assertThat(h.get("duration")).isEqualTo("under a minute");
        });
        assertThat(history).anySatisfy(h -> {
            assertThat(h).containsEntry("service", "website").containsEntry("impact", "DEGRADED");
            assertThat(h.get("resolvedAt")).isNull();
        });
        assertThat(history).allSatisfy(h -> assertThat(h).doesNotContainKeys("incidentId", "summary", "labels"));

        String page = get("/").body();
        assertThat(page).contains("Past 14 days").contains("· ongoing").contains("under a minute")
                .doesNotContain("db-primary-7").doesNotContain(resolved.toString());
    }

    @Test
    void healthAndMetrics() throws Exception {
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    private static IncidentTriggered triggered(UUID incident, String service, Severity severity) {
        return new IncidentTriggered(UUID.randomUUID(), Instant.now(), 1, incident, "test:" + incident, service, severity,
                "Connection to db-primary-7 failed: password authentication failed");
    }

    private void publish(PageRewindEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.eventId().toString());
        rabbit.send(Messaging.EVENTS_EXCHANGE, event.type().routingKey(), new Message(json.writeValueAsBytes(event), props));
    }

    private long count(String status) {
        return jdbc.sql("SELECT count(*) FROM status_incident WHERE status = ?").param(status).query(Long.class).single();
    }

    private Map<String, Object> status() throws Exception {
        HttpResponse<String> response = get("/v1/status");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, String>> levels(Map<String, Object> status) {
        return ((List<Map<String, Object>>) status.get("services")).stream()
                .map(s -> Map.entry((String) s.get("service"), (String) s.get("level")))
                .toList();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}

package dev.pagerewind.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.EventType;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.events.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pagerewind.ingest.max-body-bytes=65536")
@Import(TestcontainersConfiguration.class)
class IngestApiIT {

    private static final String TOKEN = UUID.randomUUID().toString();
    private static final String SECRET = UUID.randomUUID().toString();
    private static final String QUEUE = "it.ingest.alerts";

    @Autowired
    private Environment environment;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin admin;
    @Autowired
    private JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private Binding binding;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("pagerewind.ingest.sources.alertmanager.token", () -> TOKEN);
        registry.add("pagerewind.ingest.sources.alertmanager.hmac-secret", () -> SECRET);
        registry.add("pagerewind.ingest.sources.generic.hmac-secret", () -> SECRET);
    }

    @BeforeEach
    void bindTestQueue() {
        admin.declareQueue(new Queue(QUEUE, false, false, false));
        binding = BindingBuilder.bind(new Queue(QUEUE)).to(new TopicExchange(Messaging.EVENTS_EXCHANGE))
                .with(EventType.ALERT_RECEIVED.routingKey());
        admin.declareBinding(binding);
        admin.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void unbind() {
        admin.deleteQueue(QUEUE);
    }

    @Test
    void signedAlertmanagerWebhookIsPublishedAsDurableEvents() throws Exception {
        String fp = UUID.randomUUID().toString();
        byte[] body = alertmanagerBody(fp, "firing", 2);

        HttpResponse<String> response = post("/v1/alerts/alertmanager", body,
                "X-PageRewind-Signature", RequestAuthenticator.sign(SECRET, body));

        assertThat(response.statusCode()).isEqualTo(202);
        AlertController.IngestResponse result = json.readValue(response.body(), AlertController.IngestResponse.class);
        assertThat(result.accepted()).isEqualTo(2);

        List<Message> messages = receiveAll(2);
        assertThat(messages).hasSize(2);
        for (Message message : messages) {
            assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("alert.received");
            assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().<String>getHeader(Messaging.EVENT_TYPE_HEADER)).isEqualTo("ALERT_RECEIVED");
            AlertReceived event = json.readValue(message.getBody(), AlertReceived.class);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
            assertThat(result.eventIds()).contains(event.eventId());
            assertThat(event.service()).isEqualTo("checkout");
            assertThat(event.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(event.status()).isEqualTo(AlertStatus.FIRING);
            assertThat(event.fingerprint()).startsWith("alertmanager:" + fp);
        }
    }

    @Test
    void bearerTokenIsAccepted() throws Exception {
        HttpResponse<String> response = post("/v1/alerts/alertmanager",
                alertmanagerBody(UUID.randomUUID().toString(), "firing", 1), "Authorization", "Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(receiveAll(1)).hasSize(1);
    }

    @Test
    void missingOrWrongCredentialsAreRejectedAndNothingIsPublished() throws Exception {
        byte[] body = alertmanagerBody(UUID.randomUUID().toString(), "firing", 1);

        HttpResponse<String> none = post("/v1/alerts/alertmanager", body);
        HttpResponse<String> wrongToken = post("/v1/alerts/alertmanager", body, "Authorization", "Bearer nope");
        byte[] tampered = alertmanagerBody(UUID.randomUUID().toString(), "firing", 1);
        HttpResponse<String> tamperedBody = post("/v1/alerts/alertmanager", tampered,
                "X-PageRewind-Signature", RequestAuthenticator.sign(SECRET, body));

        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(wrongToken.statusCode()).isEqualTo(401);
        assertThat(tamperedBody.statusCode()).isEqualTo(401);
        assertThat(rabbit.receive(QUEUE, 500)).isNull();
    }

    @Test
    void genericSourceDoesNotAcceptTheAlertmanagerToken() throws Exception {
        byte[] body = genericBody("api", "k-" + UUID.randomUUID());
        assertThat(post("/v1/alerts/generic", body, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(401);
    }

    @Test
    void repeatedAlertIsFlaggedUntilItResolves() throws Exception {
        String fp = UUID.randomUUID().toString();

        AlertReceived first = sendOne(fp, "firing");
        AlertReceived second = sendOne(fp, "firing");
        AlertReceived resolved = sendOne(fp, "resolved");
        AlertReceived again = sendOne(fp, "firing");

        assertThat(first.repeat()).isFalse();
        assertThat(second.repeat()).isTrue();
        assertThat(resolved.repeat()).isFalse();
        assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(again.repeat()).isFalse();
        assertThat(List.of(first, second, resolved, again)).extracting(AlertReceived::fingerprint).containsOnly(first.fingerprint());
    }

    @Test
    void genericAlertIsPublished() throws Exception {
        String key = "k-" + UUID.randomUUID();
        byte[] body = genericBody("billing", key);

        HttpResponse<String> response = post("/v1/alerts/generic", body,
                "X-PageRewind-Signature", RequestAuthenticator.sign(SECRET, body));

        assertThat(response.statusCode()).isEqualTo(202);
        AlertReceived event = json.readValue(receiveAll(1).getFirst().getBody(), AlertReceived.class);
        assertThat(event.source()).isEqualTo("generic");
        assertThat(event.service()).isEqualTo("billing");
        assertThat(event.fingerprint()).isEqualTo("generic:" + key);
    }

    @Test
    void invalidGenericAlertIsRejectedWithDetails() throws Exception {
        byte[] body = "{\"summary\":\"no service\",\"status\":\"maybe\"}".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = post("/v1/alerts/generic", body,
                "X-PageRewind-Signature", RequestAuthenticator.sign(SECRET, body));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("application/problem+json"));
        assertThat(response.body()).contains("service").contains("status");
        assertThat(rabbit.receive(QUEUE, 300)).isNull();
    }

    @Test
    void malformedJsonAndEmptyAlertsAreRejected() throws Exception {
        byte[] malformed = "{not json".getBytes(StandardCharsets.UTF_8);
        byte[] empty = "{\"version\":\"4\",\"alerts\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] wrongShape = "[1,2,3]".getBytes(StandardCharsets.UTF_8);

        assertThat(post("/v1/alerts/alertmanager", malformed, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(400);
        assertThat(post("/v1/alerts/alertmanager", empty, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(400);
        assertThat(post("/v1/alerts/alertmanager", wrongShape, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(400);
        assertThat(rabbit.receive(QUEUE, 300)).isNull();
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        byte[] big = ("{\"padding\":\"" + "x".repeat(70_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(post("/v1/alerts/alertmanager", big, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(413);
    }

    @Test
    void unroutableEventsFailWith503SoTheSenderRetries() throws Exception {
        admin.removeBinding(binding);

        HttpResponse<String> response = post("/v1/alerts/alertmanager",
                alertmanagerBody(UUID.randomUUID().toString(), "firing", 1), "Authorization", "Bearer " + TOKEN);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    void healthMetricsAndApiDocsAreServed() throws Exception {
        post("/v1/alerts/alertmanager", alertmanagerBody(UUID.randomUUID().toString(), "firing", 1),
                "Authorization", "Bearer " + TOKEN);

        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        HttpResponse<String> metrics = get("/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("pagerewind_alerts_received_total").contains("pagerewind_alerts_rejected_total");
        HttpResponse<String> docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/v1/alerts/alertmanager").contains("/v1/alerts/generic");
    }

    private AlertReceived sendOne(String fingerprint, String status) throws Exception {
        HttpResponse<String> response = post("/v1/alerts/alertmanager", alertmanagerBody(fingerprint, status, 1),
                "Authorization", "Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readValue(receiveAll(1).getFirst().getBody(), AlertReceived.class);
    }

    private List<Message> receiveAll(int expected) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            Message message = rabbit.receive(QUEUE, 5000);
            assertThat(message).as("message %d of %d", i + 1, expected).isNotNull();
            messages.add(message);
        }
        assertThat(rabbit.receive(QUEUE, 200)).as("no extra messages").isNull();
        return messages;
    }

    private byte[] alertmanagerBody(String fingerprint, String status, int count) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            alerts.add(Map.of(
                    "status", status,
                    "labels", Map.of("alertname", "HighErrorRate", "service", "checkout", "severity", "critical",
                            "pod", "checkout-" + i),
                    "annotations", Map.of("summary", "Checkout 5xx above 5%"),
                    "startsAt", "2026-11-18T09:55:00.000Z",
                    "endsAt", "0001-01-01T00:00:00Z",
                    "generatorURL", "http://prometheus/graph",
                    "fingerprint", count == 1 ? fingerprint : fingerprint + "-" + i));
        }
        return json.writeValueAsBytes(Map.of(
                "version", "4",
                "groupKey", "{}:{alertname=\"HighErrorRate\"}",
                "status", status,
                "receiver", "pagerewind",
                "groupLabels", Map.of("alertname", "HighErrorRate"),
                "commonLabels", Map.of("alertname", "HighErrorRate"),
                "commonAnnotations", Map.of(),
                "externalURL", "http://alertmanager",
                "truncatedAlerts", 0,
                "alerts", alerts));
    }

    private byte[] genericBody(String service, String key) {
        return json.writeValueAsBytes(Map.of("service", service, "severity", "critical", "summary", "Card payments failing",
                "labels", Map.of("region", "eu"), "dedupKey", key));
    }

    private HttpResponse<String> post(String path, byte[] body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}

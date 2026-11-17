package dev.pagerewind.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.events.Contact;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.events.PageRequested;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class NotifyIT {

    private static final String BOT_TOKEN = UUID.randomUUID().toString();
    private static final String WEBHOOK_SECRET = UUID.randomUUID().toString();
    private static final String LINK_SECRET = UUID.randomUUID().toString();
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final StubServer STUB = startStub();
    @SuppressWarnings("resource")
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.20.0"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    static {
        MAILPIT.start();
    }

    private static StubServer startStub() {
        try {
            return new StubServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pagerewind.notify.telegram.bot-token", () -> BOT_TOKEN);
        registry.add("pagerewind.notify.telegram.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("pagerewind.notify.telegram.api-base-url", () -> STUB.url() + "/tg");
        registry.add("pagerewind.notify.incident-base-url", () -> STUB.url() + "/incident");
        registry.add("pagerewind.notify.action-links.secret", () -> LINK_SECRET);
        registry.add("pagerewind.notify.public-base-url", () -> "https://pagerewind.test");
        registry.add("pagerewind.notify.retry.initial-backoff", () -> "1ms");
        registry.add("pagerewind.notify.retry.max-backoff", () -> "5ms");
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private PageService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private JsonMapper json;
    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() throws Exception {
        jdbc.sql("TRUNCATE delivery, action_token_use").update();
        STUB.reset();
        http.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private static PageRequested page(UUID incident, Contact... contacts) {
        return new PageRequested(UUID.randomUUID(), Instant.now(), 1, incident, 1, 1, "alice", "Alice",
                List.of(contacts), "checkout", Severity.CRITICAL, "Checkout 5xx above 5%");
    }

    private static final Contact TELEGRAM = new Contact(Contact.Channel.TELEGRAM, "1001");
    private static final Contact EMAIL = new Contact(Contact.Channel.EMAIL, "alice@example.com");

    // ---- sending

    @Test
    void pageFromRabbitIsSentByTelegramAndEmail() throws Exception {
        UUID incident = UUID.randomUUID();
        PageRequested page = page(incident, TELEGRAM, EMAIL);
        MessageProperties props = new MessageProperties();
        props.setMessageId(page.eventId().toString());
        rabbit.send(Messaging.EVENTS_EXCHANGE, page.type().routingKey(), new Message(json.writeValueAsBytes(page), props));

        await().atMost(WAIT).untilAsserted(() -> assertThat(statuses(page.eventId())).containsExactlyInAnyOrder("SENT", "SENT"));

        List<StubServer.Request> sent = STUB.requests("/bot" + BOT_TOKEN + "/sendMessage");
        assertThat(sent).hasSize(1);
        Map<String, Object> body = json.readValue(sent.getFirst().body(), new TypeReference<Map<String, Object>>() {
        });
        assertThat(body).containsEntry("chat_id", "1001");
        assertThat((String) body.get("text")).contains("Checkout 5xx above 5%").contains("Paged: Alice");
        assertThat(sent.getFirst().body()).contains("\"callback_data\":\"ack:" + incident + "\"")
                .contains("\"callback_data\":\"res:" + incident + "\"");

        List<Map<String, Object>> mails = mails();
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().get("Subject")).isEqualTo("[PageRewind] CRITICAL checkout: Checkout 5xx above 5%");
        assertThat(mailText(mails.getFirst())).contains("https://pagerewind.test/v1/actions/");
    }

    @Test
    void sameEventHandledTwiceSendsOnce() throws Exception {
        PageRequested page = page(UUID.randomUUID(), TELEGRAM, EMAIL);
        service.handle(page);
        service.handle(page);

        assertThat(STUB.requests("/sendMessage")).hasSize(1);
        assertThat(mails()).hasSize(1);
        assertThat(statuses(page.eventId())).containsExactlyInAnyOrder("SENT", "SENT");
    }

    @Test
    void samePersonIsNotPagedTwiceForOneIncidentWithinTheRateLimit() {
        UUID incident = UUID.randomUUID();
        PageRequested first = page(incident, TELEGRAM);
        PageRequested second = page(incident, TELEGRAM);
        PageRequested otherIncident = page(UUID.randomUUID(), TELEGRAM);

        service.handle(first);
        service.handle(second);
        service.handle(otherIncident);

        assertThat(statuses(first.eventId())).containsExactly("SENT");
        assertThat(statuses(second.eventId())).containsExactly("RATE_LIMITED");
        assertThat(statuses(otherIncident.eventId())).containsExactly("SENT");
        assertThat(STUB.requests("/sendMessage")).hasSize(2);
    }

    @Test
    void transientTelegramErrorsAreRetried() {
        STUB.script("/sendMessage", 500, "{\"ok\":false}");
        STUB.script("/sendMessage", 429, "{\"ok\":false,\"parameters\":{\"retry_after\":0}}");
        PageRequested page = page(UUID.randomUUID(), TELEGRAM);

        service.handle(page);

        assertThat(STUB.requests("/sendMessage")).hasSize(3);
        assertThat(statuses(page.eventId())).containsExactly("SENT");
        assertThat(jdbc.sql("SELECT attempts FROM delivery").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void permanentTelegramErrorIsNotRetriedAndDoesNotStopEmail() throws Exception {
        STUB.script("/sendMessage", 400, "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}");
        PageRequested page = page(UUID.randomUUID(), TELEGRAM, EMAIL);

        service.handle(page);

        assertThat(STUB.requests("/sendMessage")).hasSize(1);
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE channel = 'TELEGRAM'").query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT last_error FROM delivery WHERE channel = 'TELEGRAM'").query(String.class).single())
                .contains("400").contains("chat not found");
        assertThat(jdbc.sql("SELECT status FROM delivery WHERE channel = 'EMAIL'").query(String.class).single()).isEqualTo("SENT");
        assertThat(mails()).hasSize(1);
    }

    @Test
    void giveUpAfterMaxAttempts() {
        for (int i = 0; i < 3; i++) {
            STUB.script("/sendMessage", 503, "{\"ok\":false}");
        }
        PageRequested page = page(UUID.randomUUID(), TELEGRAM);
        service.handle(page);

        assertThat(STUB.requests("/sendMessage")).hasSize(3);
        assertThat(statuses(page.eventId())).containsExactly("FAILED");
    }

    // ---- Telegram buttons

    @Test
    void buttonFromThePagedChatAcknowledgesAsThatPerson() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, TELEGRAM));

        HttpResponse<String> response = webhook(WEBHOOK_SECRET, callback("1001", "ack:" + incident));

        assertThat(response.statusCode()).isEqualTo(200);
        List<StubServer.Request> calls = STUB.requests("/incident/v1/incidents/" + incident + "/acknowledge");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().body()).isEqualTo("{\"actor\":\"alice\"}");
        assertThat(STUB.requests("/answerCallbackQuery")).hasSize(1);
        assertThat(STUB.requests("/answerCallbackQuery").getFirst().body()).contains("Incident acknowledged.");
    }

    @Test
    void resolveButtonAndAlreadyDoneAnswer() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, TELEGRAM));
        STUB.script("/resolve", 409, "{}");

        webhook(WEBHOOK_SECRET, callback("1001", "res:" + incident));

        assertThat(STUB.requests("/incident/v1/incidents/" + incident + "/resolve")).hasSize(1);
        assertThat(STUB.requests("/answerCallbackQuery").getFirst().body()).contains("already resolved");
    }

    @Test
    void buttonFromAChatThatWasNotPagedIsRefused() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, TELEGRAM));

        webhook(WEBHOOK_SECRET, callback("9999", "ack:" + incident));
        webhook(WEBHOOK_SECRET, callback("1001", "ack:" + UUID.randomUUID()));

        assertThat(STUB.requests("/acknowledge")).isEmpty();
        assertThat(STUB.requests("/answerCallbackQuery")).hasSize(2)
                .allSatisfy(r -> assertThat(r.body()).contains("You were not paged for this incident."));
    }

    @Test
    void webhookRejectsMissingOrWrongSecret() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, TELEGRAM));

        assertThat(webhook(null, callback("1001", "ack:" + incident)).statusCode()).isEqualTo(401);
        assertThat(webhook("wrong", callback("1001", "ack:" + incident)).statusCode()).isEqualTo(401);
        assertThat(STUB.requests("/acknowledge")).isEmpty();
    }

    @Test
    void webhookIgnoresOtherUpdatesAndUnknownButtons() throws Exception {
        assertThat(webhook(WEBHOOK_SECRET, "{\"update_id\":1,\"message\":{\"text\":\"hi\"}}").statusCode()).isEqualTo(200);
        assertThat(webhook(WEBHOOK_SECRET, callback("1001", "something-else")).statusCode()).isEqualTo(200);
        assertThat(webhook(WEBHOOK_SECRET, callback("1001", "ack:not-a-uuid")).statusCode()).isEqualTo(200);
        assertThat(STUB.requests("/incident/")).isEmpty();
    }

    // ---- email links

    @Test
    void emailLinkConfirmsOnGetActsOnPostAndWorksOnce() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, EMAIL));
        String text = mailText(mails().getFirst());
        String ackPath = link(text, "Acknowledge");
        String resolvePath = link(text, "Resolve");

        HttpResponse<String> confirm = send(HttpRequest.newBuilder(local(ackPath)).GET().build());
        assertThat(confirm.statusCode()).isEqualTo(200);
        assertThat(confirm.body()).contains("Acknowledge incident").contains("<form method=\"post\">");
        assertThat(confirm.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(STUB.requests("/acknowledge")).as("opening the link must not act").isEmpty();

        HttpResponse<String> done = send(HttpRequest.newBuilder(local(ackPath)).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(done.body()).contains("Incident acknowledged.");
        assertThat(STUB.requests("/incident/v1/incidents/" + incident + "/acknowledge")).hasSize(1);
        assertThat(STUB.requests("/acknowledge").getFirst().body()).isEqualTo("{\"actor\":\"alice\"}");

        HttpResponse<String> again = send(HttpRequest.newBuilder(local(ackPath)).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(STUB.requests("/acknowledge")).hasSize(1);

        HttpResponse<String> resolve = send(HttpRequest.newBuilder(local(resolvePath)).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(resolve.statusCode()).isEqualTo(200);
        assertThat(STUB.requests("/incident/v1/incidents/" + incident + "/resolve")).hasSize(1);
    }

    @Test
    void tamperedLinkIsRejected() throws Exception {
        UUID incident = UUID.randomUUID();
        service.handle(page(incident, EMAIL));
        String path = link(mailText(mails().getFirst()), "Acknowledge");
        String tampered = path.substring(0, path.length() - 2) + (path.endsWith("A") ? "BB" : "AA");

        assertThat(send(HttpRequest.newBuilder(local(tampered)).GET().build()).statusCode()).isEqualTo(400);
        assertThat(send(HttpRequest.newBuilder(local(tampered)).POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode())
                .isEqualTo(400);
        assertThat(STUB.requests("/incident/")).isEmpty();
    }

    @Test
    void healthAndMetricsAreServed() throws Exception {
        service.handle(page(UUID.randomUUID(), TELEGRAM));
        assertThat(send(HttpRequest.newBuilder(local("/actuator/health/readiness")).GET().build()).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(local("/actuator/prometheus")).GET().build()).body())
                .contains("pagerewind_notify_deliveries_total");
    }

    // ---- helpers

    private List<String> statuses(UUID eventId) {
        return jdbc.sql("SELECT status FROM delivery WHERE event_id = ?").param(eventId).query(String.class).list();
    }

    private static String callback(String chatId, String data) {
        return """
                {"update_id": 10, "callback_query": {"id": "cb-%s", "from": {"id": %s, "username": "alice"},
                 "message": {"message_id": 5, "chat": {"id": %s, "type": "private"}}, "data": "%s"}}"""
                .formatted(UUID.randomUUID(), chatId, chatId, data);
    }

    private HttpResponse<String> webhook(String secret, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(local("/v1/telegram/webhook"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (secret != null) {
            request.header(TelegramWebhookController.SECRET_HEADER, secret);
        }
        return send(request.build());
    }

    private List<Map<String, Object>> mails() throws Exception {
        String body = send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).GET().build()).body();
        Map<String, Object> page = json.readValue(body, new TypeReference<Map<String, Object>>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) page.get("messages");
        return messages;
    }

    private String mailText(Map<String, Object> summary) throws Exception {
        String body = send(HttpRequest.newBuilder(mailpit("/api/v1/message/" + summary.get("ID"))).GET().build()).body();
        return (String) json.readValue(body, new TypeReference<Map<String, Object>>() {
        }).get("Text");
    }

    private static String link(String text, String label) {
        Matcher matcher = Pattern.compile(label + ": https://pagerewind\\.test(/v1/actions/[A-Za-z0-9_.-]+)").matcher(text);
        assertThat(matcher.find()).as("%s link in %s", label, text).isTrue();
        return matcher.group(1);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI local(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static URI mailpit(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
    }
}

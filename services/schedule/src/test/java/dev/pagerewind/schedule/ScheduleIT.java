package dev.pagerewind.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.events.Contact;
import dev.pagerewind.events.EventType;
import dev.pagerewind.events.IncidentAcknowledged;
import dev.pagerewind.events.IncidentResolved;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.events.PageRequested;
import dev.pagerewind.events.PageRewindEvent;
import dev.pagerewind.events.Severity;
import dev.pagerewind.messaging.OutboxRelay;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pagerewind.messaging.outbox.relay-enabled=false",
        "pagerewind.schedule.escalation-runner-enabled=false"})
@Import(TestcontainersConfiguration.class)
class ScheduleIT {

    private static final Instant T0 = Instant.parse("2026-11-18T04:00:00Z"); // 09:30 in Colombo
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String PAGES = "it.schedule.pages";

    @Autowired
    private EscalationService service;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin admin;
    @Autowired
    private JsonMapper json;
    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() throws Exception {
        clock.set(T0);
        jdbc.sql("""
                TRUNCATE person, contact, team, team_service, rotation, rotation_member, rotation_override,
                         escalation_step, incident_escalation, escalation_job, outbox, processed_event CASCADE""").update();
        admin.declareQueue(new Queue(PAGES, false, false, false));
        admin.declareBinding(BindingBuilder.bind(new Queue(PAGES)).to(new TopicExchange(Messaging.EVENTS_EXCHANGE))
                .with(EventType.PAGE_REQUESTED.routingKey()));
        admin.purgeQueue(PAGES, false);

        put("/v1/people/alice", "{\"name\":\"Alice\",\"contacts\":[{\"channel\":\"TELEGRAM\",\"address\":\"1001\"},"
                + "{\"channel\":\"EMAIL\",\"address\":\"alice@example.com\"}]}", 200);
        put("/v1/people/bob", "{\"name\":\"Bob\",\"contacts\":[{\"channel\":\"EMAIL\",\"address\":\"bob@example.com\"}]}", 200);
        put("/v1/people/carol", "{\"name\":\"Carol\",\"contacts\":[{\"channel\":\"TELEGRAM\",\"address\":\"1003\"}]}", 200);
        put("/v1/people/nocontact", "{\"name\":\"No Contact\"}", 200);
        put("/v1/teams/ops", "{\"name\":\"Operations\",\"services\":[\"checkout\",\"payments\"]}", 200);
        put("/v1/teams/ops/rotations/primary", "{\"name\":\"Primary\",\"members\":[\"alice\",\"carol\"],"
                + "\"start\":\"2026-11-18T09:00:00\",\"timeZone\":\"Asia/Colombo\",\"shiftHours\":24}", 200);
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":1,\"steps\":["
                + "{\"escalateAfterMinutes\":2,\"targetType\":\"ROTATION\",\"targetId\":\"primary\"},"
                + "{\"escalateAfterMinutes\":5,\"targetType\":\"PERSON\",\"targetId\":\"bob\"}]}", 200);
    }

    @AfterEach
    void deleteTestQueue() {
        admin.deleteQueue(PAGES);
    }

    // ---- the Day 3 escalation path

    @Test
    void pagesOnCallPersonThenEscalatesAfterTwoMinutesAndStopsOnAcknowledge() {
        UUID incident = trigger("checkout");

        List<EscalationRepository.Job> jobs = jobs(incident);
        assertThat(jobs).hasSize(4);
        assertThat(jobs).extracting(EscalationRepository.Job::fireAt)
                .containsExactly(T0, T0.plusSeconds(120), T0.plusSeconds(420), T0.plusSeconds(540));

        assertThat(service.fireDue()).isEqualTo(1);
        PageRequested first = onlyPage();
        assertThat(first.incidentId()).isEqualTo(incident);
        assertThat(first.personId()).isEqualTo("alice");
        assertThat(first.personName()).isEqualTo("Alice");
        assertThat(first.step()).isEqualTo(1);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.contacts()).containsExactlyInAnyOrder(
                new Contact(Contact.Channel.TELEGRAM, "1001"), new Contact(Contact.Channel.EMAIL, "alice@example.com"));
        assertThat(first.service()).isEqualTo("checkout");
        assertThat(first.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(first.summary()).isEqualTo("Checkout 5xx above 5%");

        clock.advance(Duration.ofSeconds(119));
        assertThat(service.fireDue()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.fireDue()).isEqualTo(1);
        PageRequested second = onlyPage();
        assertThat(second.personId()).isEqualTo("bob");
        assertThat(second.step()).isEqualTo(2);

        publish(new IncidentAcknowledged(UUID.randomUUID(), clock.instant(), 1, incident, "bob"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(incident)).isEqualTo("STOPPED"));

        clock.advance(Duration.ofHours(1));
        assertThat(service.fireDue()).isZero();
        assertThat(relay.relayOnce()).isZero();
        assertThat(jobs(incident)).extracting(EscalationRepository.Job::status)
                .containsExactly("FIRED", "FIRED", "CANCELLED", "CANCELLED");
    }

    @Test
    void repeatRoundPagesAgainWhenNobodyResponds() {
        UUID incident = trigger("checkout");

        clock.advance(Duration.ofMinutes(7));
        assertThat(service.fireDue()).isEqualTo(3);
        List<PageRequested> pages = drainPages();
        assertThat(pages).extracting(PageRequested::personId).containsExactly("alice", "bob", "alice");
        assertThat(pages).extracting(PageRequested::round).containsExactly(1, 1, 2);
    }

    @Test
    void stepUsesWhoeverIsOnCallWhenItFiresIncludingOverrides() {
        // Next day 09:00 Colombo, carol takes over the rotation.
        clock.set(Instant.parse("2026-11-19T04:00:00Z"));
        UUID first = trigger("checkout");
        service.fireDue();
        assertThat(onlyPage().personId()).isEqualTo("carol");

        post("/v1/teams/ops/rotations/primary/overrides", "{\"personId\":\"alice\",\"start\":\"2026-11-19T03:00:00Z\","
                + "\"end\":\"2026-11-19T06:00:00Z\"}", 201);
        UUID second = trigger("payments");
        service.fireDue();
        PageRequested page = onlyPage();
        assertThat(page.incidentId()).isEqualTo(second);
        assertThat(page.personId()).isEqualTo("alice");
        assertThat(first).isNotEqualTo(second);
    }

    // ---- ordering, duplicates and routing

    @Test
    void acknowledgeArrivingBeforeTriggerStartsNothing() {
        UUID incident = UUID.randomUUID();
        publish(new IncidentAcknowledged(UUID.randomUUID(), T0, 1, incident, "alice"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(incident)).isEqualTo("STOPPED"));

        IncidentTriggered late = triggered(incident, "checkout");
        assertThat(service.onTriggered(late)).isEqualTo(EscalationService.Outcome.ALREADY_KNOWN);
        assertThat(jobs(incident)).isEmpty();
    }

    @Test
    void duplicateTriggerEventCreatesJobsOnce() {
        IncidentTriggered event = triggered(UUID.randomUUID(), "checkout");
        assertThat(service.onTriggered(event)).isEqualTo(EscalationService.Outcome.STARTED);
        assertThat(service.onTriggered(event)).isEqualTo(EscalationService.Outcome.DUPLICATE);
        assertThat(jobs(event.incidentId())).hasSize(4);
    }

    @Test
    void resolveAlsoStopsTheEscalation() {
        UUID incident = trigger("checkout");
        service.fireDue();
        drainPages();

        publish(new IncidentResolved(UUID.randomUUID(), T0, 1, incident, "alice"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(incident)).isEqualTo("STOPPED"));

        clock.advance(Duration.ofHours(1));
        assertThat(service.fireDue()).isZero();
        assertThat(escalation(incident)).contains("\"stopReason\":\"RESOLVED\"");
    }

    @Test
    void unknownServiceGoesToTheDefaultTeamOrNowhere() {
        IncidentTriggered orphan = triggered(UUID.randomUUID(), "unclaimed");
        assertThat(service.onTriggered(orphan)).isEqualTo(EscalationService.Outcome.UNROUTED);
        assertThat(jobs(orphan.incidentId())).isEmpty();

        put("/v1/teams/fallback", "{\"name\":\"Fallback\",\"defaultTeam\":true}", 200);
        put("/v1/teams/fallback/escalation-policy", "{\"repeat\":0,\"steps\":["
                + "{\"escalateAfterMinutes\":5,\"targetType\":\"PERSON\",\"targetId\":\"carol\"}]}", 200);
        IncidentTriggered routed = triggered(UUID.randomUUID(), "unclaimed");
        assertThat(service.onTriggered(routed)).isEqualTo(EscalationService.Outcome.STARTED);
        service.fireDue();
        assertThat(onlyPage().personId()).isEqualTo("carol");
    }

    @Test
    void teamWithoutPolicyPagesNobody() {
        put("/v1/teams/empty", "{\"name\":\"Empty\",\"services\":[\"lonely\"]}", 200);
        assertThat(service.onTriggered(triggered(UUID.randomUUID(), "lonely"))).isEqualTo(EscalationService.Outcome.NO_POLICY);
    }

    @Test
    void stepIsSkippedWhenNobodyCanBePaged() {
        clock.set(Instant.parse("2026-11-18T03:00:00Z")); // before the rotation starts
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":0,\"steps\":["
                + "{\"escalateAfterMinutes\":1,\"targetType\":\"ROTATION\",\"targetId\":\"primary\"},"
                + "{\"escalateAfterMinutes\":1,\"targetType\":\"PERSON\",\"targetId\":\"nocontact\"}]}", 200);
        UUID incident = trigger("checkout");

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.fireDue()).isEqualTo(2);

        assertThat(jobs(incident)).extracting(EscalationRepository.Job::status).containsExactly("SKIPPED", "SKIPPED");
        assertThat(jobs(incident)).extracting(EscalationRepository.Job::note)
                .containsExactly("nobody is on call in rotation primary", "person nocontact has no contact details");
        assertThat(relay.relayOnce()).isZero();
    }

    @Test
    void timersAreAnchoredToWhenTheIncidentOpenedNotWhenTheEventArrived() {
        IncidentTriggered late = triggered(UUID.randomUUID(), "checkout");
        clock.advance(Duration.ofMinutes(3)); // the event is handled 3 minutes late
        service.onTriggered(late);

        assertThat(service.fireDue()).isEqualTo(2);
        assertThat(drainPages()).extracting(PageRequested::personId).containsExactly("alice", "bob");
    }

    @Test
    void parallelRunnersFireEachStepExactlyOnce() throws Exception {
        for (int i = 0; i < 60; i++) {
            service.onTriggered(triggered(UUID.randomUUID(), "checkout"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runs.add(pool.submit(service::fireDue));
        }
        int total = 0;
        for (Future<Integer> run : runs) {
            total += run.get();
        }
        pool.shutdown();

        assertThat(total).isEqualTo(60);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'PAGE_REQUESTED'").query(Long.class).single()).isEqualTo(60);
        assertThat(jdbc.sql("SELECT count(DISTINCT payload->>'incidentId') FROM outbox").query(Long.class).single()).isEqualTo(60);
    }

    // ---- configuration API

    @Test
    void onCallEndpointShowsCurrentShift() {
        String now = get("/v1/teams/ops/on-call", 200);
        assertThat(now).contains("\"personId\":\"alice\"").contains("\"override\":false");

        String tomorrow = get("/v1/teams/ops/on-call?at=2026-11-19T04:00:00Z", 200);
        assertThat(tomorrow).contains("\"personId\":\"carol\"");

        String team = get("/v1/teams/ops", 200);
        assertThat(team).contains("\"services\":[\"checkout\",\"payments\"]").contains("\"onCallNow\"");
    }

    @Test
    void configurationIsValidated() {
        put("/v1/people/Bad_Id", "{\"name\":\"x\"}", 400);
        put("/v1/people/dave", "{\"name\":\"Dave\",\"contacts\":[{\"channel\":\"TELEGRAM\",\"address\":\"@dave\"}]}", 400);
        put("/v1/people/dave", "{\"name\":\"Dave\",\"contacts\":[{\"channel\":\"EMAIL\",\"address\":\"not-an-email\"}]}", 400);
        put("/v1/people/dave", "{\"contacts\":[]}", 400);
        put("/v1/teams/ops/rotations/r2", "{\"name\":\"R\",\"members\":[\"ghost\"],\"start\":\"2026-11-18T09:00:00\","
                + "\"timeZone\":\"Asia/Colombo\",\"shiftHours\":24}", 400);
        put("/v1/teams/ops/rotations/r2", "{\"name\":\"R\",\"members\":[\"alice\"],\"start\":\"2026-11-18T09:00:00\","
                + "\"timeZone\":\"Mars/Base\",\"shiftHours\":24}", 400);
        put("/v1/teams/ops/rotations/r2", "{\"name\":\"R\",\"members\":[\"alice\"],\"start\":\"2026-11-18T09:00:00\","
                + "\"timeZone\":\"UTC\",\"shiftHours\":0}", 400);
        put("/v1/teams/ops/rotations/r2", "{\"name\":\"R\",\"members\":[],\"start\":\"2026-11-18T09:00:00\","
                + "\"timeZone\":\"UTC\",\"shiftHours\":24}", 400);
        put("/v1/teams/nope/rotations/r2", "{\"name\":\"R\",\"members\":[\"alice\"],\"start\":\"2026-11-18T09:00:00\","
                + "\"timeZone\":\"UTC\",\"shiftHours\":24}", 404);
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":0,\"steps\":[{\"escalateAfterMinutes\":5,"
                + "\"targetType\":\"ROTATION\",\"targetId\":\"missing\"}]}", 404);
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":0,\"steps\":[{\"escalateAfterMinutes\":5,"
                + "\"targetType\":\"PERSON\",\"targetId\":\"ghost\"}]}", 400);
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":0,\"steps\":[]}", 400);
        put("/v1/teams/ops/escalation-policy", "{\"repeat\":11,\"steps\":[{\"escalateAfterMinutes\":5,"
                + "\"targetType\":\"PERSON\",\"targetId\":\"bob\"}]}", 400);
        post("/v1/teams/ops/rotations/primary/overrides", "{\"personId\":\"bob\",\"start\":\"2026-11-19T06:00:00Z\","
                + "\"end\":\"2026-11-19T05:00:00Z\"}", 400);
        get("/v1/escalations/" + UUID.randomUUID(), 404);
    }

    @Test
    void overviewPageShowsWhoIsOnCallAndEscapesNames() {
        put("/v1/people/eve", "{\"name\":\"<img src=x onerror=alert(1)>\",\"contacts\":[]}", 200);
        put("/v1/teams/ops", "{\"name\":\"Ops <b>team</b>\",\"services\":[\"checkout\"]}", 200);

        String page = get("/ui", 200);

        assertThat(page).contains("Who is on call").contains("Primary").contains(">alice<").contains("alice, carol")
                .contains("Services: checkout").contains("On call in primary, then wait 2 min")
                .contains("Ops &lt;b&gt;team&lt;/b&gt;").doesNotContain("<b>team</b>").doesNotContain("<img src=x");
    }

    @Test
    void onlyOneDefaultTeam() {
        put("/v1/teams/a", "{\"name\":\"A\",\"defaultTeam\":true}", 200);
        put("/v1/teams/b", "{\"name\":\"B\",\"defaultTeam\":true}", 200);
        assertThat(jdbc.sql("SELECT id FROM team WHERE is_default").query(String.class).list()).containsExactly("b");
    }

    @Test
    void healthAndMetricsAreServed() {
        trigger("checkout");
        service.fireDue();
        get("/actuator/health/liveness", 200);
        get("/actuator/health/readiness", 200);
        assertThat(get("/actuator/prometheus", 200))
                .contains("pagerewind_escalation_steps_fired_total")
                .contains("pagerewind_escalation_events_total");
        assertThat(get("/v3/api-docs", 200)).contains("/v1/teams/{teamId}/escalation-policy");
    }

    // ---- helpers

    private IncidentTriggered triggered(UUID incident, String service) {
        return new IncidentTriggered(UUID.randomUUID(), clock.instant(), 1, incident, "test:" + incident, service,
                Severity.CRITICAL, "Checkout 5xx above 5%");
    }

    /** Publishes a trigger event through RabbitMQ and waits until the escalation exists. */
    private UUID trigger(String service) {
        UUID incident = UUID.randomUUID();
        publish(triggered(incident, service));
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(incident)).isEqualTo("ACTIVE"));
        return incident;
    }

    private void publish(PageRewindEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.eventId().toString());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(Messaging.EVENTS_EXCHANGE, event.type().routingKey(), new Message(json.writeValueAsBytes(event), props));
    }

    private String status(UUID incident) {
        return jdbc.sql("SELECT status FROM incident_escalation WHERE incident_id = ?").param(incident)
                .query(String.class).optional().orElse(null);
    }

    private List<EscalationRepository.Job> jobs(UUID incident) {
        return new EscalationRepository(jdbc).jobs(incident);
    }

    private String escalation(UUID incident) {
        return get("/v1/escalations/" + incident, 200);
    }

    /** Relays the outbox and returns the single page that was published. */
    private PageRequested onlyPage() {
        List<PageRequested> pages = drainPages();
        assertThat(pages).hasSize(1);
        return pages.getFirst();
    }

    private List<PageRequested> drainPages() {
        while (relay.relayOnce() > 0) {
            // keep going until the outbox is empty
        }
        List<PageRequested> pages = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(PAGES, 1000)) != null) {
            assertThat(message.getMessageProperties().<String>getHeader(Messaging.EVENT_TYPE_HEADER)).isEqualTo("PAGE_REQUESTED");
            pages.add(json.readValue(message.getBody(), PageRequested.class));
        }
        return pages;
    }

    private String get(String path, int expectedStatus) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build(), expectedStatus);
    }

    private String put(String path, String body, int expectedStatus) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), expectedStatus);
    }

    private String post(String path, String body, int expectedStatus) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), expectedStatus);
    }

    private String send(HttpRequest request, int expectedStatus) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s -> %s", request.method(), request.uri().getPath(), response.body())
                    .isEqualTo(expectedStatus);
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}

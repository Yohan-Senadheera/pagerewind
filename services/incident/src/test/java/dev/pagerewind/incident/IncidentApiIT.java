package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.core.type.TypeReference;

class IncidentApiIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID openIncident() {
        String fingerprint = newFingerprint();
        service.handleAlert(alert(fingerprint));
        return jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
    }

    @Test
    void getReturnsIncidentWithTimeline() throws Exception {
        UUID id = openIncident();

        HttpResponse<String> response = get("/v1/incidents/" + id);

        assertThat(response.statusCode()).isEqualTo(200);
        IncidentController.IncidentDetail detail = json.readValue(response.body(), IncidentController.IncidentDetail.class);
        assertThat(detail.incident().id()).isEqualTo(id);
        assertThat(detail.incident().status()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(detail.incident().alertCount()).isEqualTo(1);
        assertThat(detail.incident().labels()).containsEntry("alertname", "HighErrorRate");
        assertThat(detail.timeline()).extracting(TimelineEntry::type)
                .containsExactly(TimelineEntry.Type.TRIGGERED, TimelineEntry.Type.ALERT);
    }

    @Test
    void acknowledgeThenResolveFollowsTheStateMachine() throws Exception {
        UUID id = openIncident();

        HttpResponse<String> ack = post("/v1/incidents/" + id + "/acknowledge", "{\"actor\":\"alice\",\"note\":\"looking\"}");
        assertThat(ack.statusCode()).isEqualTo(200);
        Incident acknowledged = json.readValue(ack.body(), Incident.class);
        assertThat(acknowledged.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedAt()).isNotNull();
        assertThat(acknowledged.version()).isEqualTo(1);

        HttpResponse<String> ackAgain = post("/v1/incidents/" + id + "/acknowledge", null);
        assertThat(ackAgain.statusCode()).isEqualTo(409);
        assertThat(ackAgain.body()).contains("ACKNOWLEDGED");

        HttpResponse<String> resolve = post("/v1/incidents/" + id + "/resolve", null);
        assertThat(resolve.statusCode()).isEqualTo(200);
        Incident resolved = json.readValue(resolve.body(), Incident.class);
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.acknowledgedAt()).isEqualTo(acknowledged.acknowledgedAt());
        assertThat(resolved.version()).isEqualTo(2);

        assertThat(post("/v1/incidents/" + id + "/resolve", null).statusCode()).isEqualTo(409);
        assertThat(post("/v1/incidents/" + id + "/acknowledge", null).statusCode()).isEqualTo(409);

        List<TimelineEntry> timeline = service.timeline(id);
        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(TimelineEntry.Type.TRIGGERED,
                TimelineEntry.Type.ALERT, TimelineEntry.Type.ACKNOWLEDGED, TimelineEntry.Type.RESOLVED);
        assertThat(timeline.get(2).actor()).isEqualTo("alice");
        assertThat(timeline.get(2).data()).containsEntry("note", "looking");
        assertThat(timeline.get(3).actor()).isEqualTo(IncidentController.DEFAULT_ACTOR);

        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'INCIDENT_ACKNOWLEDGED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'INCIDENT_RESOLVED'")).isEqualTo(1);
    }

    @Test
    void triggeredIncidentCanBeResolvedDirectly() throws Exception {
        UUID id = openIncident();
        HttpResponse<String> resolve = post("/v1/incidents/" + id + "/resolve", "{}");
        assertThat(resolve.statusCode()).isEqualTo(200);
        assertThat(json.readValue(resolve.body(), Incident.class).acknowledgedAt()).isNull();
    }

    @Test
    void concurrentAcknowledgementsHaveExactlyOneWinner() throws Exception {
        UUID id = openIncident();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String body = "{\"actor\":\"user-" + i + "\"}";
            results.add(pool.submit(() -> {
                start.await();
                return post("/v1/incidents/" + id + "/acknowledge", body).statusCode();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM incident_event WHERE type = 'ACKNOWLEDGED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'INCIDENT_ACKNOWLEDGED'")).isEqualTo(1);
        assertThat(service.get(id).version()).isEqualTo(1);
    }

    @Test
    void staleVersionIsRejectedByTheDatabase() {
        UUID id = openIncident();
        Incident before = service.get(id);
        service.acknowledge(id, "alice", null);

        IncidentRepository repository = new IncidentRepository(jdbc, json);
        boolean changed = repository.changeStatus(id, before.version(), IncidentStatus.RESOLVED, java.time.Instant.now());

        assertThat(changed).isFalse();
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
    }

    @Test
    void notesAreAddedToTheTimeline() throws Exception {
        UUID id = openIncident();

        HttpResponse<String> created = post("/v1/incidents/" + id + "/notes", "{\"actor\":\"bob\",\"text\":\"  rolled back v42  \"}");
        HttpResponse<String> blank = post("/v1/incidents/" + id + "/notes", "{\"text\":\"   \"}");
        HttpResponse<String> missing = post("/v1/incidents/" + UUID.randomUUID() + "/notes", "{\"text\":\"x\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(missing.statusCode()).isEqualTo(404);
        TimelineEntry note = service.timeline(id).getLast();
        assertThat(note.type()).isEqualTo(TimelineEntry.Type.NOTE);
        assertThat(note.message()).isEqualTo("rolled back v42");
        assertThat(note.actor()).isEqualTo("bob");
    }

    @Test
    void listFiltersByStatusAndValidatesInput() throws Exception {
        UUID open = openIncident();
        UUID closed = openIncident();
        service.resolve(closed, "alice", null);

        List<Incident> all = json.readValue(get("/v1/incidents").body(), new TypeReference<List<Incident>>() {
        });
        List<Incident> triggered = json.readValue(get("/v1/incidents?status=TRIGGERED").body(), new TypeReference<List<Incident>>() {
        });
        List<Incident> limited = json.readValue(get("/v1/incidents?limit=1").body(), new TypeReference<List<Incident>>() {
        });

        assertThat(all).extracting(Incident::id).containsExactlyInAnyOrder(open, closed);
        assertThat(triggered).extracting(Incident::id).containsExactly(open);
        assertThat(limited).hasSize(1);
        assertThat(get("/v1/incidents?limit=0").statusCode()).isEqualTo(400);
        assertThat(get("/v1/incidents?limit=201").statusCode()).isEqualTo(400);
        assertThat(get("/v1/incidents?status=BOGUS").statusCode()).isEqualTo(400);
    }

    @Test
    void unknownAndMalformedIdsAreReported() throws Exception {
        HttpResponse<String> unknown = get("/v1/incidents/" + UUID.randomUUID());
        assertThat(unknown.statusCode()).isEqualTo(404);
        Map<String, Object> problem = json.readValue(unknown.body(), new TypeReference<Map<String, Object>>() {
        });
        assertThat(problem).containsEntry("status", 404).containsKey("detail");

        assertThat(get("/v1/incidents/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(post("/v1/incidents/" + UUID.randomUUID() + "/acknowledge", null).statusCode()).isEqualTo(404);
    }

    @Test
    void oversizedActionFieldsAreRejected() throws Exception {
        UUID id = openIncident();
        String longActor = "a".repeat(201);
        assertThat(post("/v1/incidents/" + id + "/acknowledge", "{\"actor\":\"" + longActor + "\"}").statusCode()).isEqualTo(400);
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.TRIGGERED);
    }

    @Test
    void healthMetricsAndApiDocsAreServed() throws Exception {
        openIncident();

        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        HttpResponse<String> metrics = get("/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("pagerewind_incidents_created_total").contains("pagerewind_outbox_pending");
        HttpResponse<String> docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("/v1/incidents/{id}/acknowledge");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}

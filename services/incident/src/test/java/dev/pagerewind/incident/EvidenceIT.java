package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.EvidenceCaptured;
import dev.pagerewind.events.Messaging;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Evidence from the capture service lands in the timeline once and shows on the incident page. */
class EvidenceIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    private UUID openIncident() {
        String fingerprint = newFingerprint();
        service.handleAlert(alert(fingerprint));
        return jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
    }

    private void publish(EvidenceCaptured evidence) {
        MessageProperties props = new MessageProperties();
        props.setHeader(Messaging.EVENT_TYPE_HEADER, "EVIDENCE_CAPTURED");
        props.setMessageId(evidence.eventId().toString());
        rabbit.send(Messaging.EVENTS_EXCHANGE, EventType.EVIDENCE_CAPTURED.routingKey(),
                new Message(json.writeValueAsBytes(evidence), props));
    }

    private String page(UUID id, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + environment.getProperty("local.server.port") + "/ui/incidents/" + id))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void evidenceIsRecordedOnceAndShownWithTheLikelyCause() throws Exception {
        UUID id = openIncident();
        EvidenceCaptured evidence = new EvidenceCaptured(UUID.randomUUID(), Instant.now(), 1, id, 2, 140, 3, 9_876,
                "Deployment checkout changed image of container app to shop/checkout:1.5, 4 minutes before the alert (same workload)");

        publish(evidence);
        publish(evidence);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(service.timeline(id))
                .filteredOn(e -> e.type() == TimelineEntry.Type.EVIDENCE).hasSize(1));
        Thread.sleep(500);
        TimelineEntry entry = service.timeline(id).stream().filter(e -> e.type() == TimelineEntry.Type.EVIDENCE)
                .toList().getFirst();
        assertThat(service.timeline(id)).filteredOn(e -> e.type() == TimelineEntry.Type.EVIDENCE).hasSize(1);
        assertThat(entry.message()).isEqualTo("Evidence captured: 140 log lines from 2 containers, 3 changes in the hour before");
        assertThat(entry.actor()).isEqualTo("capture");
        assertThat(entry.data()).containsEntry("likelyCause", evidence.likelyCause());

        String responder = page(id, TOKENS.person("alice", "responder"));
        assertThat(responder).contains("Likely cause:").contains("Deployment checkout changed image of container app")
                .contains("/ui/evidence/" + id);
        String viewer = page(id, TOKENS.person("vera", "viewer"));
        assertThat(viewer).contains("Likely cause:").doesNotContain("/ui/evidence/" + id);
    }

    @Test
    void evidenceWithoutALikelyCauseSaysSo() throws Exception {
        UUID id = openIncident();
        publish(new EvidenceCaptured(UUID.randomUUID(), Instant.now(), 1, id, 1, 12, 0, 800, null));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(service.timeline(id))
                .anyMatch(e -> e.type() == TimelineEntry.Type.EVIDENCE));
        assertThat(page(id, TOKENS.person("alice", "responder"))).contains("No changes were recorded in the hour before the alert.");
    }

    @Test
    void evidenceForAnUnknownIncidentIsIgnored() {
        UUID unknown = UUID.randomUUID();
        service.recordEvidence(new EvidenceCaptured(UUID.randomUUID(), Instant.now(), 1, unknown, 1, 1, 0, 10, null));

        assertThat(count("SELECT count(*) FROM incident_event WHERE incident_id = ?", unknown)).isZero();
    }
}

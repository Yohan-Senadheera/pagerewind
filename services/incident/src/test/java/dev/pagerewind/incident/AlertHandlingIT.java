package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.EventType;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.incident.IncidentService.AlertOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

class AlertHandlingIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    void sameAlertSentHundredTimesThroughRabbitCreatesOneIncidentWithHundredAlerts() {
        String fingerprint = newFingerprint();
        for (int i = 0; i < 100; i++) {
            publish(alert(fingerprint));
        }

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM processed_event")).isEqualTo(100));

        assertThat(count("SELECT count(*) FROM incident WHERE fingerprint = ?", fingerprint)).isEqualTo(1);
        assertThat(count("SELECT alert_count FROM incident WHERE fingerprint = ?", fingerprint)).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM incident_event WHERE type = 'ALERT'")).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM incident_event WHERE type = 'TRIGGERED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'INCIDENT_TRIGGERED'")).isEqualTo(1);
    }

    @Test
    void hundredConcurrentCallsForSameFingerprintCreateExactlyOneIncident() throws Exception {
        String fingerprint = newFingerprint();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AlertOutcome>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            AlertReceived event = alert(fingerprint);
            results.add(pool.submit(() -> {
                start.await();
                return service.handleAlert(event);
            }));
        }
        start.countDown();
        List<AlertOutcome> outcomes = new ArrayList<>();
        for (Future<AlertOutcome> result : results) {
            outcomes.add(result.get());
        }
        pool.shutdown();

        Map<AlertOutcome, Long> byOutcome = outcomes.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(byOutcome).containsEntry(AlertOutcome.CREATED, 1L).containsEntry(AlertOutcome.ATTACHED, 99L);
        assertThat(count("SELECT count(*) FROM incident WHERE fingerprint = ?", fingerprint)).isEqualTo(1);
        assertThat(count("SELECT alert_count FROM incident WHERE fingerprint = ?", fingerprint)).isEqualTo(100);
    }

    @Test
    void sameEventHandledTwiceCountsOnce() {
        AlertReceived event = alert(newFingerprint());

        assertThat(service.handleAlert(event)).isEqualTo(AlertOutcome.CREATED);
        assertThat(service.handleAlert(event)).isEqualTo(AlertOutcome.DUPLICATE);
        assertThat(service.handleAlert(event)).isEqualTo(AlertOutcome.DUPLICATE);

        assertThat(count("SELECT alert_count FROM incident WHERE fingerprint = ?", event.fingerprint())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM incident_event WHERE type = 'ALERT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    }

    @Test
    void sameMessageRedeliveredByBrokerIsHandledOnce() {
        AlertReceived event = alert(newFingerprint());
        double duplicatesBefore = counter("pagerewind.incident.alerts.handled", "outcome", "duplicate");

        for (int i = 0; i < 5; i++) {
            publish(event);
        }

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(counter("pagerewind.incident.alerts.handled", "outcome", "duplicate") - duplicatesBefore).isEqualTo(4));
        assertThat(count("SELECT alert_count FROM incident WHERE fingerprint = ?", event.fingerprint())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_event")).isEqualTo(1);
    }

    @Test
    void afterResolveTheSameFingerprintOpensANewIncident() {
        String fingerprint = newFingerprint();
        service.handleAlert(alert(fingerprint));
        UUID first = jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
        service.resolve(first, "alice", null);

        assertThat(service.handleAlert(alert(fingerprint))).isEqualTo(AlertOutcome.CREATED);

        assertThat(count("SELECT count(*) FROM incident WHERE fingerprint = ?", fingerprint)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM incident WHERE fingerprint = ? AND status <> 'RESOLVED'", fingerprint)).isEqualTo(1);
        assertThat(service.get(first).status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(service.get(first).alertCount()).isEqualTo(1);
    }

    @Test
    void resolvedAlertIsRecordedButDoesNotCloseOrOpenIncidents() {
        String fingerprint = newFingerprint();

        assertThat(service.handleAlert(alert(fingerprint, AlertStatus.RESOLVED))).isEqualTo(AlertOutcome.RESOLVED_IGNORED);
        assertThat(count("SELECT count(*) FROM incident")).isZero();

        service.handleAlert(alert(fingerprint));
        assertThat(service.handleAlert(alert(fingerprint, AlertStatus.RESOLVED))).isEqualTo(AlertOutcome.RESOLVED_RECORDED);

        UUID id = jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(service.timeline(id)).extracting(TimelineEntry::type)
                .containsExactly(TimelineEntry.Type.TRIGGERED, TimelineEntry.Type.ALERT, TimelineEntry.Type.ALERT_RESOLVED);
    }

    @Test
    void newIncidentHasTriggeredEventInOutboxWithTheSameData() {
        AlertReceived event = alert(newFingerprint());
        service.handleAlert(event);

        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE event_type = 'INCIDENT_TRIGGERED'").query(String.class).single();
        dev.pagerewind.events.IncidentTriggered triggered = json.readValue(payload, dev.pagerewind.events.IncidentTriggered.class);
        UUID id = jdbc.sql("SELECT id FROM incident").query(UUID.class).single();
        assertThat(triggered.incidentId()).isEqualTo(id);
        assertThat(triggered.fingerprint()).isEqualTo(event.fingerprint());
        assertThat(triggered.service()).isEqualTo(event.service());
        assertThat(triggered.severity()).isEqualTo(event.severity());
        assertThat(count("SELECT count(*) FROM outbox WHERE routing_key = 'incident.triggered'")).isEqualTo(1);
    }

    @Test
    void messageThatAlwaysFailsGoesToDeadLetterQueueAfterFiveAttempts() {
        double failuresBefore = counter("pagerewind.incident.alerts.failed");
        String messageId = UUID.randomUUID().toString();
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(Messaging.EVENTS_EXCHANGE, EventType.ALERT_RECEIVED.routingKey(),
                new Message("{not valid json".getBytes(StandardCharsets.UTF_8), props));

        Message[] deadLettered = new Message[1];
        await().atMost(WAIT).untilAsserted(() -> {
            deadLettered[0] = rabbit.receive(properties.alertsDeadLetterQueue(), 500);
            assertThat(deadLettered[0]).isNotNull();
        });

        assertThat(deadLettered[0].getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(counter("pagerewind.incident.alerts.failed") - failuresBefore)
                .isEqualTo(properties.deliveryLimit() + 1)
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM processed_event")).isZero();
    }

    @Test
    void invalidEventIsDeadLetteredAndDoesNotBlockValidOnes() {
        String bad = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-11-18T10:00:00Z\",\"version\":1,"
                + "\"source\":\"x\",\"service\":\"x\",\"severity\":\"CRITICAL\",\"status\":\"FIRING\",\"summary\":\"x\","
                + "\"fingerprint\":\"\",\"repeat\":false,\"startsAt\":\"2026-11-18T10:00:00Z\"}";
        rabbit.send(Messaging.EVENTS_EXCHANGE, EventType.ALERT_RECEIVED.routingKey(),
                new Message(bad.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        AlertReceived good = alert(newFingerprint());
        publish(good);

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM incident WHERE fingerprint = ?", good.fingerprint())).isEqualTo(1));
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(rabbit.receive(properties.alertsDeadLetterQueue(), 500)).isNotNull());
    }

    private void publish(AlertReceived event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.eventId().toString());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(Messaging.EVENTS_EXCHANGE, EventType.ALERT_RECEIVED.routingKey(),
                new Message(json.writeValueAsBytes(event), props));
    }
}

package dev.pagerewind.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertNormalizerTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private final AlertNormalizer normalizer = new AlertNormalizer(Clock.fixed(NOW, ZoneOffset.UTC));

    private static AlertmanagerPayload payload(String status, AlertmanagerPayload.Alert... alerts) {
        return new AlertmanagerPayload("4", status, "pagerewind", Map.of("namespace", "shop"), "http://am", List.of(alerts));
    }

    private static AlertmanagerPayload.Alert amAlert(String status, Map<String, String> labels,
            Map<String, String> annotations, String fingerprint) {
        return new AlertmanagerPayload.Alert(status, labels, annotations, Instant.parse("2026-11-18T09:55:00Z"),
                null, "http://prom/graph", fingerprint);
    }

    @Test
    void mapsAlertmanagerFields() {
        List<AlertReceived> events = normalizer.fromAlertmanager(payload("firing", amAlert("firing",
                Map.of("alertname", "HighErrorRate", "service", "checkout", "severity", "critical"),
                Map.of("summary", "  5xx above 5%  ", "description", "long text"), "abc123")));

        assertThat(events).hasSize(1);
        AlertReceived e = events.getFirst();
        assertThat(e.source()).isEqualTo("alertmanager");
        assertThat(e.service()).isEqualTo("checkout");
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.status()).isEqualTo(AlertStatus.FIRING);
        assertThat(e.summary()).isEqualTo("5xx above 5%");
        assertThat(e.fingerprint()).isEqualTo("alertmanager:abc123");
        assertThat(e.labels()).containsEntry("alertname", "HighErrorRate");
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-11-18T09:55:00Z"));
        assertThat(e.occurredAt()).isEqualTo(NOW);
        assertThat(e.version()).isEqualTo(AlertReceived.CURRENT_VERSION);
        assertThat(e.repeat()).isFalse();
    }

    @Test
    void serviceFallsBackThroughLabelsThenCommonLabels() {
        assertThat(service(Map.of("app", "a", "job", "j"))).isEqualTo("a");
        assertThat(service(Map.of("job", "j", "namespace", "n"))).isEqualTo("j");
        assertThat(service(Map.of("alertname", "X"))).isEqualTo("shop");

        AlertmanagerPayload noCommon = new AlertmanagerPayload("4", "firing", "r", null, null,
                List.of(amAlert("firing", Map.of("alertname", "X"), Map.of(), "f")));
        assertThat(normalizer.fromAlertmanager(noCommon).getFirst().service()).isEqualTo("unknown");
    }

    private String service(Map<String, String> labels) {
        return normalizer.fromAlertmanager(payload("firing", amAlert("firing", labels, Map.of(), "f"))).getFirst().service();
    }

    @Test
    void summaryFallsBackToDescriptionThenAlertName() {
        assertThat(summary(Map.of("description", "desc"), Map.of("alertname", "Name"))).isEqualTo("desc");
        assertThat(summary(Map.of(), Map.of("alertname", "Name"))).isEqualTo("Name");
        assertThat(summary(Map.of("summary", " "), Map.of())).isEqualTo("Alert");
    }

    private String summary(Map<String, String> annotations, Map<String, String> labels) {
        return normalizer.fromAlertmanager(payload("firing", amAlert("firing", labels, annotations, "f"))).getFirst().summary();
    }

    @Test
    void longSummaryIsTruncated() {
        String text = "x".repeat(2000);
        String summary = summary(Map.of("summary", text), Map.of());
        assertThat(summary).hasSize(AlertNormalizer.MAX_SUMMARY_LENGTH).endsWith("…");
    }

    @Test
    void alertStatusWinsOverPayloadStatus() {
        AlertmanagerPayload mixed = payload("firing",
                amAlert("resolved", Map.of("alertname", "A"), Map.of(), "a"),
                amAlert(null, Map.of("alertname", "B"), Map.of(), "b"));
        List<AlertReceived> events = normalizer.fromAlertmanager(mixed);
        assertThat(events).extracting(AlertReceived::status).containsExactly(AlertStatus.RESOLVED, AlertStatus.FIRING);
    }

    @Test
    void missingFingerprintIsDerivedFromLabelsInAnyOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("alertname", "A");
        first.put("pod", "p-1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("pod", "p-1");
        second.put("alertname", "A");

        String a = normalizer.fromAlertmanager(payload("firing", amAlert("firing", first, Map.of(), null))).getFirst().fingerprint();
        String b = normalizer.fromAlertmanager(payload("firing", amAlert("firing", second, Map.of(), " "))).getFirst().fingerprint();
        String other = normalizer.fromAlertmanager(payload("firing",
                amAlert("firing", Map.of("alertname", "A", "pod", "p-2"), Map.of(), null))).getFirst().fingerprint();

        assertThat(a).startsWith("alertmanager:").isEqualTo(b).isNotEqualTo(other);
    }

    @Test
    void everyAlertGetsItsOwnEventId() {
        List<AlertReceived> events = normalizer.fromAlertmanager(payload("firing",
                amAlert("firing", Map.of("alertname", "A"), Map.of(), "same"),
                amAlert("firing", Map.of("alertname", "A"), Map.of(), "same")));
        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
        assertThat(events.get(0).fingerprint()).isEqualTo(events.get(1).fingerprint());
    }

    @Test
    void genericUsesDedupKeyWhenGiven() {
        AlertReceived e = normalizer.fromGeneric(new GenericAlertRequest(" billing ", "p1", " Card payments failing ",
                Map.of("region", "eu"), " pay-1 ", "firing"));
        assertThat(e.source()).isEqualTo("generic");
        assertThat(e.service()).isEqualTo("billing");
        assertThat(e.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(e.summary()).isEqualTo("Card payments failing");
        assertThat(e.fingerprint()).isEqualTo("generic:pay-1");
        assertThat(e.status()).isEqualTo(AlertStatus.FIRING);
    }

    @Test
    void genericDerivedKeyDependsOnServiceAndLabelsNotSummary() {
        String a = normalizer.fromGeneric(new GenericAlertRequest("api", null, "first text", Map.of("k", "v"), null, null)).fingerprint();
        String b = normalizer.fromGeneric(new GenericAlertRequest("api", null, "second text", Map.of("k", "v"), null, null)).fingerprint();
        String c = normalizer.fromGeneric(new GenericAlertRequest("web", null, "first text", Map.of("k", "v"), null, null)).fingerprint();
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void genericWithoutLabelsUsesSummaryInKey() {
        String a = normalizer.fromGeneric(new GenericAlertRequest("api", null, "disk full", null, null, null)).fingerprint();
        String b = normalizer.fromGeneric(new GenericAlertRequest("api", null, "cpu high", null, null, null)).fingerprint();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void genericResolvedStatus() {
        AlertReceived e = normalizer.fromGeneric(new GenericAlertRequest("api", "info", "ok", null, "k", "RESOLVED"));
        assertThat(e.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(e.severity()).isEqualTo(Severity.INFO);
    }
}

package dev.pagerewind.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventValidationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private static AlertReceived alert(String fingerprint, Map<String, String> labels) {
        return new AlertReceived(UUID.randomUUID(), NOW, 1, "test", "api", Severity.WARNING,
                AlertStatus.FIRING, "summary", labels, fingerprint, false, NOW);
    }

    @Test
    void labelsAreCopiedAndImmutable() {
        Map<String, String> labels = new HashMap<>();
        labels.put("env", "prod");
        AlertReceived event = alert("fp", labels);

        labels.put("env", "changed");

        assertThat(event.labels()).containsEntry("env", "prod");
        assertThatThrownBy(() -> event.labels().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullLabelsBecomeEmpty() {
        assertThat(alert("fp", null).labels()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void blankFingerprintIsRejected(String fingerprint) {
        assertThatThrownBy(() -> alert(fingerprint, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingEventIdIsRejected() {
        assertThatNullPointerException().isThrownBy(
                () -> new IncidentAcknowledged(null, NOW, 1, UUID.randomUUID(), "alice"));
    }

    @Test
    void versionMustBePositive() {
        assertThatThrownBy(() -> new IncidentResolved(UUID.randomUUID(), NOW, 0, UUID.randomUUID(), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageStepMustBePositive() {
        assertThatThrownBy(() -> new PageRequested(UUID.randomUUID(), NOW, 1, UUID.randomUUID(), 0, "user:a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "critical,CRITICAL", "CRIT,CRITICAL", "P1,CRITICAL", "error,CRITICAL", "page,CRITICAL",
            "warning,WARNING", "warn,WARNING", "medium,WARNING", "something-else,WARNING", "NULL,WARNING", "'',WARNING",
            "info,INFO", "Low,INFO", "none,INFO", "p4,INFO"})
    void severityParsing(String input, Severity expected) {
        assertThat(Severity.parse(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "resolved,RESOLVED", "RESOLVED,RESOLVED", " resolved ,RESOLVED",
            "firing,FIRING", "NULL,FIRING", "anything,FIRING"})
    void alertStatusParsing(String input, AlertStatus expected) {
        assertThat(AlertStatus.parse(input)).isEqualTo(expected);
    }
}

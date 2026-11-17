package dev.pagerewind.incident;

import static dev.pagerewind.incident.IncidentStatus.ACKNOWLEDGED;
import static dev.pagerewind.incident.IncidentStatus.RESOLVED;
import static dev.pagerewind.incident.IncidentStatus.TRIGGERED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class IncidentStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @CsvSource({
            "TRIGGERED,TRIGGERED,false",
            "TRIGGERED,ACKNOWLEDGED,true",
            "TRIGGERED,RESOLVED,true",
            "ACKNOWLEDGED,TRIGGERED,false",
            "ACKNOWLEDGED,ACKNOWLEDGED,false",
            "ACKNOWLEDGED,RESOLVED,true",
            "RESOLVED,TRIGGERED,false",
            "RESOLVED,ACKNOWLEDGED,false",
            "RESOLVED,RESOLVED,false"})
    void transitions(IncidentStatus from, IncidentStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void onlyResolvedIsClosed(IncidentStatus status) {
        assertThat(status.isOpen()).isEqualTo(status != RESOLVED);
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void resolvedIsFinal(IncidentStatus target) {
        assertThat(RESOLVED.canMoveTo(target)).isFalse();
        assertThat(TRIGGERED.canMoveTo(target)).isEqualTo(target == ACKNOWLEDGED || target == RESOLVED);
    }
}

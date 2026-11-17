package dev.pagerewind.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EventTypeTest {

    @Test
    void routingKeysAreUnique() {
        long distinct = Arrays.stream(EventType.values()).map(EventType::routingKey).distinct().count();
        assertThat(distinct).isEqualTo(EventType.values().length);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void routingKeyRoundTrips(EventType type) {
        assertThat(EventType.fromRoutingKey(type.routingKey())).contains(type);
    }

    @Test
    void unknownRoutingKeyIsEmpty() {
        assertThat(EventType.fromRoutingKey("nope")).isEmpty();
    }

    @Test
    void everyEventReportsItsOwnType() {
        UUID id = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(new AlertReceived(id, now, 1, "test", "api", Severity.CRITICAL, AlertStatus.FIRING,
                "down", Map.of(), "fp", false, now).type()).isEqualTo(EventType.ALERT_RECEIVED);
        assertThat(new IncidentTriggered(id, now, 1, incident, "fp", "api", Severity.CRITICAL, "down").type())
                .isEqualTo(EventType.INCIDENT_TRIGGERED);
        assertThat(new IncidentAcknowledged(id, now, 1, incident, "alice").type())
                .isEqualTo(EventType.INCIDENT_ACKNOWLEDGED);
        assertThat(new IncidentResolved(id, now, 1, incident, "alice").type())
                .isEqualTo(EventType.INCIDENT_RESOLVED);
        assertThat(new PageRequested(id, now, 1, incident, 1, "user:alice").type())
                .isEqualTo(EventType.PAGE_REQUESTED);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void eventClassMatchesType(EventType type) {
        assertThat(PageRewindEvent.class).isAssignableFrom(type.eventClass());
    }
}

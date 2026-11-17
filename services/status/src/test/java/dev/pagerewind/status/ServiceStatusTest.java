package dev.pagerewind.status;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.Severity;
import dev.pagerewind.status.ServiceStatus.Level;
import dev.pagerewind.status.ServiceStatus.OpenIncident;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceStatusTest {

    private static final Instant T = Instant.parse("2026-11-18T10:00:00Z");

    @Test
    void criticalMeansMajorOutageOtherwiseDegraded() {
        List<ServiceStatus> result = ServiceStatus.compute(List.of("api", "web", "db"), List.of(
                new OpenIncident("api", Severity.WARNING, T),
                new OpenIncident("api", Severity.CRITICAL, T.plusSeconds(60)),
                new OpenIncident("web", Severity.INFO, T)));

        assertThat(result).extracting(ServiceStatus::service).containsExactly("api", "db", "web");
        assertThat(result).extracting(ServiceStatus::level).containsExactly(Level.MAJOR_OUTAGE, Level.OPERATIONAL, Level.DEGRADED);
        assertThat(result.get(0).openIncidents()).isEqualTo(2);
        assertThat(result.get(0).since()).isEqualTo(T);
        assertThat(result.get(1).since()).isNull();
        assertThat(ServiceStatus.overall(result)).isEqualTo(Level.MAJOR_OUTAGE);
    }

    @Test
    void servicesWithIncidentsAreListedEvenIfNotConfigured() {
        List<ServiceStatus> result = ServiceStatus.compute(List.of(), List.of(new OpenIncident("new", Severity.WARNING, null)));
        assertThat(result).singleElement().satisfies(s -> {
            assertThat(s.service()).isEqualTo("new");
            assertThat(s.level()).isEqualTo(Level.DEGRADED);
            assertThat(s.since()).isNull();
        });
    }

    @Test
    void nothingOpenIsOperational() {
        List<ServiceStatus> result = ServiceStatus.compute(List.of("api"), List.of());
        assertThat(ServiceStatus.overall(result)).isEqualTo(Level.OPERATIONAL);
        assertThat(ServiceStatus.overall(List.of())).isEqualTo(Level.OPERATIONAL);
    }
}

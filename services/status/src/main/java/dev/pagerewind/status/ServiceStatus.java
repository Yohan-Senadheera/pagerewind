package dev.pagerewind.status;

import dev.pagerewind.events.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Status of one service, worked out from its open incidents. */
public record ServiceStatus(String service, Level level, int openIncidents, Instant since) {

    public enum Level {
        OPERATIONAL("Operational"),
        DEGRADED("Degraded performance"),
        MAJOR_OUTAGE("Major outage");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** An open incident as the status page sees it. */
    public record OpenIncident(String service, Severity severity, Instant openedAt) {
    }

    /**
     * Any open critical incident means a major outage; any other open incident means degraded.
     * Acknowledged incidents still count: someone is working on it, but it is not fixed.
     */
    public static List<ServiceStatus> compute(List<String> knownServices, List<OpenIncident> open) {
        Map<String, List<OpenIncident>> byService = new TreeMap<>();
        knownServices.forEach(s -> byService.putIfAbsent(s, new ArrayList<>()));
        for (OpenIncident incident : open) {
            byService.computeIfAbsent(incident.service(), s -> new ArrayList<>()).add(incident);
        }
        List<ServiceStatus> result = new ArrayList<>();
        byService.forEach((service, incidents) -> {
            Level level = incidents.isEmpty() ? Level.OPERATIONAL
                    : incidents.stream().anyMatch(i -> i.severity() == Severity.CRITICAL) ? Level.MAJOR_OUTAGE
                    : Level.DEGRADED;
            Instant since = incidents.stream().map(OpenIncident::openedAt).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            result.add(new ServiceStatus(service, level, incidents.size(), since));
        });
        return result;
    }

    public static Level overall(List<ServiceStatus> services) {
        return services.stream().map(ServiceStatus::level).max(Comparator.naturalOrder()).orElse(Level.OPERATIONAL);
    }
}

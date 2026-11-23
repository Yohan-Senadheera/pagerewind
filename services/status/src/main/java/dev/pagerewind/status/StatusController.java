package dev.pagerewind.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** The public status page (HTML) and the same data as JSON. */
@Controller
public class StatusController {

    public record StatusView(String title, ServiceStatus.Level overall, String overallLabel, List<ServiceStatus> services,
            int historyDays, List<PastIncident> history, Instant generatedAt) {
    }

    private final StatusRepository repository;
    private final StatusProperties properties;
    private final Clock clock;

    public StatusController(StatusRepository repository, StatusProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    StatusView view() {
        Set<String> known = new LinkedHashSet<>(properties.services());
        known.addAll(repository.knownServices());
        List<ServiceStatus> services = ServiceStatus.compute(List.copyOf(known), repository.openIncidents());
        ServiceStatus.Level overall = ServiceStatus.overall(services);
        Instant now = clock.instant();
        List<PastIncident> history = repository.incidentsSince(now.minus(Duration.ofDays(properties.historyDays())), 50);
        return new StatusView(properties.title(), overall, overall.label(), services, properties.historyDays(), history, now);
    }

    @GetMapping("/")
    public String page(Model model) {
        model.addAttribute("view", view());
        return "status";
    }

    @GetMapping("/v1/status")
    @ResponseBody
    public ResponseEntity<StatusView> json() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(view());
    }
}

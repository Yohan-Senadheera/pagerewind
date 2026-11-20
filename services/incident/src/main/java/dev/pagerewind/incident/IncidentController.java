package dev.pagerewind.incident;

import dev.pagerewind.security.Callers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Incident> list(@RequestParam(required = false) IncidentStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(status, limit);
    }

    @GetMapping("/{id}")
    public IncidentDetail get(@PathVariable UUID id) {
        return new IncidentDetail(service.get(id), service.timeline(id));
    }

    /** People are recorded as themselves; {@code actor} is used only when a service acts for someone. */
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('responder')")
    public Incident acknowledge(@PathVariable UUID id, @Valid @RequestBody(required = false) ActionRequest request,
            Authentication caller) {
        ActionRequest action = request == null ? ActionRequest.EMPTY : request;
        return service.acknowledge(id, Callers.actor(caller, action.actor()), action.note());
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('responder')")
    public Incident resolve(@PathVariable UUID id, @Valid @RequestBody(required = false) ActionRequest request,
            Authentication caller) {
        ActionRequest action = request == null ? ActionRequest.EMPTY : request;
        return service.resolve(id, Callers.actor(caller, action.actor()), action.note());
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasRole('responder')")
    @ResponseStatus(HttpStatus.CREATED)
    public void addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request, Authentication caller) {
        service.addNote(id, Callers.actor(caller, request.actor()), request.text());
    }

    public record IncidentDetail(Incident incident, List<TimelineEntry> timeline) {
    }

    public record ActionRequest(@Size(max = 200) String actor, @Size(max = 4000) String note) {

        static final ActionRequest EMPTY = new ActionRequest(null, null);
    }

    public record NoteRequest(@Size(max = 200) String actor, @NotBlank @Size(max = 4000) String text) {
    }
}

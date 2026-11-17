package dev.pagerewind.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Server-rendered pages for responders. Logins are added with the gateway; until then this is internal only. */
@Controller
@RequestMapping("/ui")
public class IncidentUiController {

    private static final String UI_ACTOR = "web";

    private final IncidentService service;

    public IncidentUiController(IncidentService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "open") String show, Model model) {
        List<Incident> incidents = service.list(null, 200);
        if (show.equals("open")) {
            incidents = incidents.stream().filter(i -> i.status().isOpen()).toList();
        }
        model.addAttribute("incidents", incidents);
        model.addAttribute("show", show.equals("open") ? "open" : "all");
        return "incidents";
    }

    @GetMapping("/incidents/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("incident", service.get(id));
        model.addAttribute("timeline", service.timeline(id));
        return "incident";
    }

    @PostMapping("/incidents/{id}/acknowledge")
    public String acknowledge(@PathVariable UUID id, RedirectAttributes redirect) {
        return act(id, redirect, () -> service.acknowledge(id, UI_ACTOR, null), "Incident acknowledged.");
    }

    @PostMapping("/incidents/{id}/resolve")
    public String resolve(@PathVariable UUID id, RedirectAttributes redirect) {
        return act(id, redirect, () -> service.resolve(id, UI_ACTOR, null), "Incident resolved.");
    }

    private String act(UUID id, RedirectAttributes redirect, Runnable action, String success) {
        try {
            action.run();
            redirect.addFlashAttribute("message", success);
        } catch (IncidentErrors.InvalidTransition | IncidentErrors.ConcurrentChange e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ui/incidents/" + id;
    }
}

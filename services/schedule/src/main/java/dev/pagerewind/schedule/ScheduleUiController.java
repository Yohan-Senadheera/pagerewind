package dev.pagerewind.schedule;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only overview of who is on call, reached through the gateway at the same path. */
@Controller
public class ScheduleUiController {

    private final ScheduleController api;
    private final DirectoryRepository directory;

    public ScheduleUiController(ScheduleController api, DirectoryRepository directory) {
        this.api = api;
        this.directory = directory;
    }

    @GetMapping("/ui/schedules")
    public String overview(Model model, Authentication caller) {
        model.addAttribute("currentUser", caller.getName());
        List<ScheduleController.TeamView> teams = directory.findTeams().stream().map(t -> api.team(t.id())).toList();
        model.addAttribute("teams", teams);
        model.addAttribute("people", directory.findPeople());
        return "schedules";
    }
}

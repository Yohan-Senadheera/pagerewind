package dev.pagerewind.schedule;

import dev.pagerewind.events.Contact;
import dev.pagerewind.schedule.DirectoryRepository.Person;
import dev.pagerewind.schedule.DirectoryRepository.Team;
import dev.pagerewind.schedule.EscalationRepository.Escalation;
import dev.pagerewind.schedule.EscalationRepository.Job;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Configuration API for people, teams, rotations and escalation policies. */
@RestController
@RequestMapping("/v1")
public class ScheduleController {

    static final String ID = "^[a-z0-9][a-z0-9._-]{0,62}$";

    private final DirectoryRepository directory;
    private final EscalationRepository escalations;
    private final Clock clock;

    public ScheduleController(DirectoryRepository directory, EscalationRepository escalations, Clock clock) {
        this.directory = directory;
        this.escalations = escalations;
        this.clock = clock;
    }

    // ---- people

    public record PersonRequest(@NotBlank @Size(max = 200) String name, @Valid @Size(max = 10) List<ContactRequest> contacts) {
    }

    public record ContactRequest(@NotNull Contact.Channel channel, @NotBlank @Size(max = 320) String address) {
    }

    @PutMapping("/people/{id}")
    @Transactional
    public Person putPerson(@PathVariable @Pattern(regexp = ID) String id, @Valid @RequestBody PersonRequest request) {
        List<Contact> contacts = new ArrayList<>();
        for (ContactRequest c : request.contacts() == null ? List.<ContactRequest>of() : request.contacts()) {
            String address = c.address().trim();
            if (c.channel() == Contact.Channel.TELEGRAM && !address.matches("-?\\d{1,20}")) {
                throw new ScheduleErrors.BadRequest("A Telegram contact must be a numeric chat id");
            }
            if (c.channel() == Contact.Channel.EMAIL && !address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new ScheduleErrors.BadRequest("Invalid email address: " + address);
            }
            contacts.add(new Contact(c.channel(), address));
        }
        directory.savePerson(id, request.name().trim(), contacts);
        return directory.findPerson(id).orElseThrow();
    }

    @GetMapping("/people")
    public List<Person> people() {
        return directory.findPeople();
    }

    // ---- teams

    public record TeamRequest(@NotBlank @Size(max = 200) String name, Boolean defaultTeam,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> services) {
    }

    public record RotationView(Rotation rotation, OnCall.Shift onCallNow) {
    }

    public record TeamView(Team team, List<String> services, List<RotationView> rotations, List<EscalationStep> policy) {
    }

    @PutMapping("/teams/{id}")
    @Transactional
    public TeamView putTeam(@PathVariable @Pattern(regexp = ID) String id, @Valid @RequestBody TeamRequest request) {
        List<String> services = request.services() == null ? List.of() : request.services().stream().map(String::trim).distinct().toList();
        directory.saveTeam(id, request.name().trim(), Boolean.TRUE.equals(request.defaultTeam()), services);
        return team(id);
    }

    @GetMapping("/teams")
    public List<Team> teams() {
        return directory.findTeams();
    }

    @GetMapping("/teams/{id}")
    public TeamView team(@PathVariable String id) {
        Team team = directory.findTeam(id).orElseThrow(() -> new ScheduleErrors.NotFound("Team " + id + " does not exist"));
        Instant now = clock.instant();
        List<RotationView> rotations = directory.rotationsOf(id).stream()
                .map(r -> new RotationView(r, OnCall.at(r, directory.overrides(r.id(), now), now).orElse(null)))
                .toList();
        return new TeamView(team, directory.servicesOf(id), rotations, directory.steps(id));
    }

    // ---- rotations

    public record RotationRequest(@NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = 100) List<@NotBlank String> members,
            @NotNull LocalDateTime start, @NotBlank String timeZone,
            @NotNull @Min(1) @Max(672) Integer shiftHours) {
    }

    @PutMapping("/teams/{teamId}/rotations/{id}")
    @Transactional
    public Rotation putRotation(@PathVariable String teamId, @PathVariable @Pattern(regexp = ID) String id,
            @Valid @RequestBody RotationRequest request) {
        requireTeam(teamId);
        directory.findRotation(id).filter(r -> !r.teamId().equals(teamId)).ifPresent(r -> {
            throw new ScheduleErrors.BadRequest("Rotation " + id + " belongs to team " + r.teamId());
        });
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timeZone());
        } catch (DateTimeException e) {
            throw new ScheduleErrors.BadRequest("Unknown time zone: " + request.timeZone());
        }
        for (String member : request.members()) {
            if (!directory.personExists(member)) {
                throw new ScheduleErrors.BadRequest("Person " + member + " does not exist");
            }
        }
        Rotation rotation = new Rotation(id, teamId, request.name().trim(), request.members(), request.start(), zone,
                request.shiftHours());
        directory.saveRotation(rotation);
        return rotation;
    }

    public record OverrideRequest(@NotBlank String personId, @NotNull Instant start, @NotNull Instant end) {
    }

    @PostMapping("/teams/{teamId}/rotations/{id}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RotationOverride addOverride(@PathVariable String teamId, @PathVariable String id,
            @Valid @RequestBody OverrideRequest request) {
        Rotation rotation = requireRotation(teamId, id);
        if (!request.end().isAfter(request.start())) {
            throw new ScheduleErrors.BadRequest("The override must end after it starts");
        }
        if (!directory.personExists(request.personId())) {
            throw new ScheduleErrors.BadRequest("Person " + request.personId() + " does not exist");
        }
        long overrideId = directory.addOverride(rotation.id(), request.personId(), request.start(), request.end());
        return new RotationOverride(overrideId, rotation.id(), request.personId(), request.start(), request.end());
    }

    public record OnCallEntry(String rotationId, String rotationName, OnCall.Shift shift) {
    }

    @GetMapping("/teams/{teamId}/on-call")
    public List<OnCallEntry> onCall(@PathVariable String teamId, @RequestParam(required = false) Instant at) {
        requireTeam(teamId);
        Instant when = at == null ? clock.instant() : at;
        return directory.rotationsOf(teamId).stream()
                .map(r -> new OnCallEntry(r.id(), r.name(), OnCall.at(r, directory.overrides(r.id(), when), when).orElse(null)))
                .toList();
    }

    // ---- escalation policy

    public record PolicyRequest(@Min(0) @Max(10) Integer repeat, @NotEmpty @Size(max = 20) List<@Valid StepRequest> steps) {
    }

    public record StepRequest(@NotNull @Min(1) @Max(1440) Integer escalateAfterMinutes, @NotNull EscalationStep.TargetType targetType,
            @NotBlank String targetId) {
    }

    @PutMapping("/teams/{teamId}/escalation-policy")
    @Transactional
    public List<EscalationStep> putPolicy(@PathVariable String teamId, @Valid @RequestBody PolicyRequest request) {
        requireTeam(teamId);
        List<EscalationStep> steps = new ArrayList<>();
        for (int i = 0; i < request.steps().size(); i++) {
            StepRequest step = request.steps().get(i);
            switch (step.targetType()) {
                case PERSON -> {
                    if (!directory.personExists(step.targetId())) {
                        throw new ScheduleErrors.BadRequest("Step " + (i + 1) + ": person " + step.targetId() + " does not exist");
                    }
                }
                case ROTATION -> requireRotation(teamId, step.targetId());
            }
            steps.add(new EscalationStep(i + 1, step.escalateAfterMinutes(), step.targetType(), step.targetId()));
        }
        directory.savePolicy(teamId, request.repeat() == null ? 0 : request.repeat(), steps);
        return directory.steps(teamId);
    }

    // ---- escalations (read only)

    public record EscalationView(Escalation escalation, List<Job> jobs) {
    }

    @GetMapping("/escalations/{incidentId}")
    public EscalationView escalation(@PathVariable UUID incidentId) {
        Escalation escalation = escalations.find(incidentId)
                .orElseThrow(() -> new ScheduleErrors.NotFound("No escalation for incident " + incidentId));
        return new EscalationView(escalation, escalations.jobs(incidentId));
    }

    private void requireTeam(String teamId) {
        if (directory.findTeam(teamId).isEmpty()) {
            throw new ScheduleErrors.NotFound("Team " + teamId + " does not exist");
        }
    }

    private Rotation requireRotation(String teamId, String rotationId) {
        requireTeam(teamId);
        return directory.findRotation(rotationId).filter(r -> r.teamId().equals(teamId))
                .orElseThrow(() -> new ScheduleErrors.NotFound("Rotation " + rotationId + " does not exist in team " + teamId));
    }
}

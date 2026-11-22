package dev.pagerewind.capture;

import dev.pagerewind.capture.ChangeRanking.Ranked;
import dev.pagerewind.capture.ChangeRanking.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only views of what the recorder holds. Log lines are only exported inside evidence bundles for
 * responders; this API shows counts and change metadata.
 */
@RestController
@RequestMapping("/v1")
class CaptureController {

    record RecorderStatus(Set<String> namespaces, int containers, int followedContainers, long bufferBytes, long droppedLines) {
    }

    record ChangesView(Instant at, Target target, Ranked likelyCause, List<Ranked> changes) {
    }

    private final RecordedNamespaces namespaces;
    private final LogBuffer buffer;
    private final PodLogFollower follower;
    private final ChangeRepository changes;
    private final ChangeRanking ranking;
    private final CaptureProperties properties;
    private final Clock clock;

    CaptureController(RecordedNamespaces namespaces, LogBuffer buffer, PodLogFollower follower, ChangeRepository changes,
            ChangeRanking ranking, CaptureProperties properties, Clock clock) {
        this.namespaces = namespaces;
        this.buffer = buffer;
        this.follower = follower;
        this.changes = changes;
        this.ranking = ranking;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/recorder")
    RecorderStatus recorder() {
        return new RecorderStatus(namespaces.current(), buffer.containers(), follower.followedContainers(),
                buffer.totalBytes(), buffer.droppedLines());
    }

    /** The changes before a moment (default now), ranked for a namespace and workload, with the likely cause. */
    @GetMapping("/changes")
    @PreAuthorize("hasRole('responder')")
    ChangesView changes(@RequestParam(required = false) String namespace, @RequestParam(required = false) String workload,
            @RequestParam(required = false) Instant at) {
        Instant when = at == null ? clock.instant() : at;
        Duration lookBack = properties.lookBack();
        Target target = new Target(namespace, workload);
        List<Ranked> ranked = ranking.rank(changes.between(when.minus(lookBack), when), target, when);
        return new ChangesView(when, target, ranked.isEmpty() ? null : ranked.getFirst(), ranked);
    }
}

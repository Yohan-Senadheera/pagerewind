package dev.pagerewind.capture;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ranks the changes before an alert to point at the most likely cause.
 *
 * <p>score = scope × time × kind. Scope: same workload 3, same namespace 1.5, cluster-wide 1, another
 * namespace 0.3. Time: 1 for a change right before the alert, falling linearly to 0.1 at the edge of the
 * look-back window. Kind: how often that kind of change breaks things ({@link ChangeEvent.Kind#weight()}).
 * Changes after the alert or outside the window are ignored.
 */
final class ChangeRanking {

    /**
     * Kubernetes builds generated name suffixes from this alphabet: no vowels, so they never spell words
     * and "node-exporter" is not mistaken for a hash.
     */
    private static final String SAFE = "[bcdfghjklmnpqrstvwxz2456789]";
    /** Pod names made by a Deployment: {@code <deployment>-<replicaset hash>-<5 chars>}. */
    private static final Pattern DEPLOYMENT_POD = Pattern.compile("^(.+)-" + SAFE + "{6,10}-" + SAFE + "{5}$");
    /** Pod names made by a StatefulSet (ordinal) or DaemonSet or Job (5 chars). */
    private static final Pattern OTHER_POD = Pattern.compile("^(.+)-([0-9]+|" + SAFE + "{5})$");

    /** What the alert is about, from its labels. */
    record Target(String namespace, String workload) {

        static Target fromLabels(Map<String, String> labels) {
            String namespace = first(labels, "namespace", "exported_namespace", "kubernetes_namespace");
            String workload = first(labels, "deployment", "statefulset", "daemonset", "workload", "app",
                    "app_kubernetes_io_name", "container");
            if (workload == null) {
                workload = workloadOfPod(first(labels, "pod", "pod_name", "kubernetes_pod_name"));
            }
            return new Target(namespace, workload);
        }

        private static String first(Map<String, String> labels, String... keys) {
            for (String key : keys) {
                String value = labels.get(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }
    }

    record Ranked(ChangeEvent change, double score, String reason) {
    }

    private final Duration lookBack;

    ChangeRanking(Duration lookBack) {
        this.lookBack = lookBack;
    }

    static String workloadOfPod(String pod) {
        if (pod == null || pod.isBlank()) {
            return null;
        }
        var deployment = DEPLOYMENT_POD.matcher(pod);
        if (deployment.matches()) {
            return deployment.group(1);
        }
        var other = OTHER_POD.matcher(pod);
        return other.matches() ? other.group(1) : pod;
    }

    List<Ranked> rank(List<ChangeEvent> changes, Target target, Instant alertAt) {
        Instant from = alertAt.minus(lookBack);
        List<ChangeEvent> window = changes.stream()
                .filter(c -> !c.occurredAt().isAfter(alertAt) && !c.occurredAt().isBefore(from))
                .toList();
        Map<ChangeEvent, History> history = imageHistory(window);
        return window.stream()
                .map(c -> score(c, target, alertAt, history.get(c)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(r -> r.change().occurredAt(), Comparator.reverseOrder()))
                .toList();
    }

    Optional<Ranked> likelyCause(List<ChangeEvent> changes, Target target, Instant alertAt) {
        return rank(changes, target, alertAt).stream().findFirst();
    }

    /** What happened to an image change afterwards, or what it did relative to earlier ones. */
    record History(Status status, ChangeEvent related) {
    }

    enum Status {
        /** A later change undid it (put back the image it replaced). */
        REVERTED,
        /** It undid the change just before it. */
        ROLLBACK,
        /** It deploys an image that an earlier change had already rolled back. */
        REDEPLOYED_ROLLED_BACK
    }

    /**
     * Walks the image changes of each container in time order. A change that undoes the one just before it
     * (A to B, then B to A) is a rollback, unless that one was itself a rollback. A change to an image that
     * was rolled back earlier is a re-deploy of a known-bad image.
     */
    static Map<ChangeEvent, History> imageHistory(List<ChangeEvent> changes) {
        Map<ChangeEvent, History> history = new HashMap<>();
        Map<String, List<ChangeEvent>> perContainer = new HashMap<>();
        for (ChangeEvent change : changes) {
            if (change.kind() == ChangeEvent.Kind.IMAGE && change.details().get("from") != null) {
                String key = change.namespace() + "/" + change.name() + "/" + change.details().get("container");
                perContainer.computeIfAbsent(key, k -> new ArrayList<>()).add(change);
            }
        }
        for (List<ChangeEvent> sequence : perContainer.values()) {
            sequence.sort(Comparator.comparing(ChangeEvent::occurredAt));
            Map<String, ChangeEvent> rolledBackImages = new HashMap<>();
            ChangeEvent previous = null;
            for (ChangeEvent change : sequence) {
                String to = change.details().get("to");
                String fromImage = change.details().get("from");
                boolean previousWasRollback = previous != null && history.containsKey(previous)
                        && history.get(previous).status() == Status.ROLLBACK;
                boolean undoesPrevious = previous != null && !previousWasRollback
                        && Objects.equals(to, previous.details().get("from"))
                        && Objects.equals(fromImage, previous.details().get("to"));
                if (undoesPrevious) {
                    history.put(change, new History(Status.ROLLBACK, previous));
                    history.put(previous, new History(Status.REVERTED, change));
                    rolledBackImages.put(previous.details().get("to"), change);
                } else if (rolledBackImages.containsKey(to)) {
                    history.put(change, new History(Status.REDEPLOYED_ROLLED_BACK, rolledBackImages.get(to)));
                }
                previous = change;
            }
        }
        return history;
    }

    private Ranked score(ChangeEvent change, Target target, Instant alertAt, History history) {
        Duration before = Duration.between(change.occurredAt(), alertAt);
        double time = Math.max(0.1, 1.0 - (double) before.toSeconds() / lookBack.toSeconds());
        Scope scope = scope(change, target);
        double score = scope.weight * time * change.kind().weight();
        String reason = "%s, %s before the alert (%s)".formatted(change.summary(), human(before), scope.label);
        if (history != null) {
            switch (history.status()) {
                case REVERTED -> {
                    // Still relevant (its damage may outlast it), but less likely the current cause.
                    score *= 0.5;
                    reason += "; reverted %s later".formatted(human(Duration.between(change.occurredAt(),
                            history.related().occurredAt())));
                }
                case ROLLBACK -> {
                    // Putting back the image that ran before rarely causes an incident.
                    score *= 0.2;
                    reason += "; this was a rollback";
                }
                case REDEPLOYED_ROLLED_BACK -> {
                    // The same image was rolled back once already: a strong sign it is bad.
                    score *= 1.2;
                    reason += "; this image was rolled back %s earlier".formatted(human(Duration.between(
                            history.related().occurredAt(), change.occurredAt())));
                }
            }
        }
        return new Ranked(change, Math.round(score * 1000) / 1000.0, reason);
    }

    private enum Scope {
        SAME_WORKLOAD(3.0, "same workload"),
        SAME_NAMESPACE(1.5, "same namespace"),
        CLUSTER(1.0, "cluster-wide"),
        UNKNOWN(1.0, "alert has no namespace"),
        OTHER_NAMESPACE(0.3, "another namespace");

        final double weight;
        final String label;

        Scope(double weight, String label) {
            this.weight = weight;
            this.label = label;
        }
    }

    private static Scope scope(ChangeEvent change, Target target) {
        if (change.namespace() == null) {
            return Scope.CLUSTER;
        }
        if (target.namespace() == null || !target.namespace().equals(change.namespace())) {
            return target.namespace() == null ? Scope.UNKNOWN : Scope.OTHER_NAMESPACE;
        }
        if (target.workload() != null
                && (target.workload().equals(change.workload()) || target.workload().equals(change.name()))) {
            return Scope.SAME_WORKLOAD;
        }
        return Scope.SAME_NAMESPACE;
    }

    private static String human(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return duration.toSeconds() + " seconds";
        }
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}

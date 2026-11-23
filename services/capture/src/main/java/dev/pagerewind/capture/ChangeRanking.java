package dev.pagerewind.capture;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
        return window.stream()
                .map(c -> score(c, target, alertAt, window))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(r -> r.change().occurredAt(), Comparator.reverseOrder()))
                .toList();
    }

    /**
     * A later image change on the same container that puts back the image this one replaced: this change
     * was reverted, so whatever it broke is probably fixed again.
     */
    private static Optional<ChangeEvent> revertOf(ChangeEvent change, List<ChangeEvent> all) {
        if (change.kind() != ChangeEvent.Kind.IMAGE || change.details().get("from") == null) {
            return Optional.empty();
        }
        return all.stream()
                .filter(later -> later.kind() == ChangeEvent.Kind.IMAGE && later.occurredAt().isAfter(change.occurredAt()))
                .filter(later -> Objects.equals(later.namespace(), change.namespace()) && Objects.equals(later.name(), change.name()))
                .filter(later -> Objects.equals(later.details().get("container"), change.details().get("container")))
                .filter(later -> Objects.equals(later.details().get("to"), change.details().get("from")))
                .findFirst();
    }

    /** This change itself puts back an image that an earlier change in the window had replaced. */
    private static boolean isRollback(ChangeEvent change, List<ChangeEvent> all) {
        return all.stream().anyMatch(earlier -> revertOf(earlier, all).filter(r -> r.equals(change)).isPresent());
    }

    Optional<Ranked> likelyCause(List<ChangeEvent> changes, Target target, Instant alertAt) {
        return rank(changes, target, alertAt).stream().findFirst();
    }

    private Ranked score(ChangeEvent change, Target target, Instant alertAt, List<ChangeEvent> window) {
        Duration before = Duration.between(change.occurredAt(), alertAt);
        double time = Math.max(0.1, 1.0 - (double) before.toSeconds() / lookBack.toSeconds());
        Scope scope = scope(change, target);
        double score = scope.weight * time * change.kind().weight();
        String reason = "%s, %s before the alert (%s)".formatted(change.summary(), human(before), scope.label);
        Optional<ChangeEvent> revert = revertOf(change, window);
        if (revert.isPresent()) {
            // Still relevant (it may have caused damage that outlasted it), but less likely the current cause.
            score *= 0.5;
            reason += "; reverted %s later".formatted(human(Duration.between(change.occurredAt(), revert.get().occurredAt())));
        } else if (isRollback(change, window)) {
            // Putting back a known-good image rarely causes an incident.
            score *= 0.2;
            reason += "; this was a rollback";
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

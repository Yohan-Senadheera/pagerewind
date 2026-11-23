package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.capture.ChangeEvent.Kind;
import dev.pagerewind.capture.ChangeRanking.Ranked;
import dev.pagerewind.capture.ChangeRanking.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChangeRankingTest {

    private static final Instant ALERT = Instant.parse("2026-11-23T10:00:00Z");
    private final ChangeRanking ranking = new ChangeRanking(Duration.ofHours(1));

    private static ChangeEvent change(int minutesBefore, Kind kind, String namespace, String name, String workload) {
        return ChangeEvent.of(ALERT.minus(Duration.ofMinutes(minutesBefore)), kind, namespace, name, workload,
                kind.label() + " of " + name, Map.of());
    }

    @Test
    void anImageChangeToTheAlertingWorkloadWins() {
        ChangeEvent badDeploy = change(4, Kind.IMAGE, "shop", "checkout", "checkout");
        List<ChangeEvent> changes = List.of(
                change(2, Kind.SCALE, "shop", "search", "search"),
                change(1, Kind.NODE, null, "agent", null),
                badDeploy,
                change(3, Kind.IMAGE, "billing", "invoices", "invoices"));

        Ranked top = ranking.likelyCause(changes, new Target("shop", "checkout"), ALERT).orElseThrow();

        assertThat(top.change()).isEqualTo(badDeploy);
        assertThat(top.reason()).isEqualTo("image change of checkout, 4 minutes before the alert (same workload)");
    }

    @Test
    void closerChangesScoreHigherThanOlderOnes() {
        ChangeEvent recent = change(5, Kind.CONFIG, "shop", "checkout-config", "checkout");
        ChangeEvent old = change(50, Kind.IMAGE, "shop", "checkout", "checkout");

        List<Ranked> ranked = ranking.rank(List.of(old, recent), new Target("shop", "checkout"), ALERT);

        assertThat(ranked).extracting(Ranked::change).containsExactly(recent, old);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void changesAfterTheAlertOrOutsideTheWindowAreIgnored() {
        ChangeEvent after = ChangeEvent.of(ALERT.plusSeconds(30), Kind.IMAGE, "shop", "checkout", "checkout", "late", Map.of());
        ChangeEvent tooOld = change(61, Kind.IMAGE, "shop", "checkout", "checkout");
        ChangeEvent inside = change(59, Kind.SCALE, "shop", "checkout", "checkout");

        assertThat(ranking.rank(List.of(after, tooOld, inside), new Target("shop", "checkout"), ALERT))
                .extracting(Ranked::change).containsExactly(inside);
    }

    @Test
    void scopeOrderIsWorkloadThenNamespaceThenClusterThenElsewhere() {
        List<ChangeEvent> changes = List.of(
                change(10, Kind.IMAGE, "billing", "invoices", "invoices"),
                change(10, Kind.IMAGE, null, "agent", null),
                change(10, Kind.IMAGE, "shop", "search", "search"),
                change(10, Kind.IMAGE, "shop", "checkout", "checkout"));

        assertThat(ranking.rank(changes, new Target("shop", "checkout"), ALERT))
                .extracting(r -> r.reason().substring(r.reason().indexOf('(')))
                .containsExactly("(same workload)", "(same namespace)", "(cluster-wide)", "(another namespace)");
    }

    private static ChangeEvent image(int minutesBefore, String from, String to) {
        return ChangeEvent.of(ALERT.minus(Duration.ofMinutes(minutesBefore)), Kind.IMAGE, "demo", "checkout", "checkout",
                "Deployment checkout changed image of container app to " + to, Map.of("container", "app", "from", from, "to", to));
    }

    @Test
    void aRollbackIsNotTheLikelyCauseAndTheRevertedChangeSaysSo() {
        ChangeEvent bad = image(29, "busybox:1.37", "busybox:1.36");
        ChangeEvent rollback = image(28, "busybox:1.36", "busybox:1.37");

        List<Ranked> ranked = ranking.rank(List.of(rollback, bad), new Target("demo", "checkout"), ALERT);

        assertThat(ranked.get(0).change()).isEqualTo(bad);
        assertThat(ranked.get(0).reason()).endsWith("(same workload); reverted 1 minute later");
        assertThat(ranked.get(1).change()).isEqualTo(rollback);
        assertThat(ranked.get(1).reason()).endsWith("(same workload); this was a rollback");
    }

    @Test
    void aBadDeployThatIsStillLiveScoresHigherThanOneThatWasReverted() {
        ChangeEvent live = image(5, "busybox:1.37", "busybox:1.36");
        ChangeEvent reverted = image(5, "busybox:1.37", "busybox:1.36");
        ChangeEvent rollback = image(3, "busybox:1.36", "busybox:1.37");
        Target target = new Target("demo", "checkout");

        double liveScore = ranking.rank(List.of(live), target, ALERT).getFirst().score();
        double revertedScore = ranking.rank(List.of(reverted, rollback), target, ALERT).stream()
                .filter(r -> r.change().equals(reverted)).findFirst().orElseThrow().score();

        assertThat(revertedScore).isLessThan(liveScore);
    }

    @Test
    void redeployingAnImageThatWasRolledBackIsFlaggedNotCalledARollback() {
        // The live sequence of 23 Nov: bad deploy, Argo CD puts the good image back, the bad image again.
        ChangeEvent bad = image(59, "busybox:1.37", "busybox:1.36");
        ChangeEvent rollback = image(58, "busybox:1.36", "busybox:1.37");
        ChangeEvent badAgain = ChangeEvent.of(ALERT.minusSeconds(17), Kind.IMAGE, "demo", "checkout", "checkout",
                "Deployment checkout changed image of container app to busybox:1.36",
                Map.of("container", "app", "from", "busybox:1.37", "to", "busybox:1.36"));

        List<Ranked> ranked = ranking.rank(List.of(bad, rollback, badAgain), new Target("demo", "checkout"), ALERT);

        assertThat(ranked.getFirst().change()).isEqualTo(badAgain);
        assertThat(ranked.getFirst().reason()).isEqualTo("Deployment checkout changed image of container app to busybox:1.36, "
                + "17 seconds before the alert (same workload); this image was rolled back 57 minutes earlier");
        assertThat(ranked).filteredOn(r -> r.change().equals(rollback)).singleElement()
                .satisfies(r -> assertThat(r.reason()).endsWith("this was a rollback"));
        assertThat(ranked).filteredOn(r -> r.change().equals(bad)).singleElement()
                .satisfies(r -> assertThat(r.reason()).endsWith("reverted 1 minute later"));
    }

    @Test
    void independentContainersDoNotAffectEachOther() {
        ChangeEvent app = image(10, "a:1", "a:2");
        ChangeEvent sidecar = ChangeEvent.of(ALERT.minus(Duration.ofMinutes(9)), Kind.IMAGE, "demo", "checkout", "checkout",
                "sidecar change", Map.of("container", "proxy", "from", "a:2", "to", "a:1"));

        assertThat(ChangeRanking.imageHistory(List.of(app, sidecar))).isEmpty();
    }

    @Test
    void noChangesMeansNoLikelyCause() {
        assertThat(ranking.likelyCause(List.of(), new Target("shop", "checkout"), ALERT)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "checkout-7d9f8c6b5-x2k4p, checkout",
        "payment-api-5b7c9d8f6-qwxrz, payment-api",
        "node-exporter-x7k2p, node-exporter",
        "exporter-pod, exporter-pod",
        "rabbitmq-0, rabbitmq",
        "standalone, standalone",
    })
    void workloadIsDerivedFromThePodName(String pod, String workload) {
        assertThat(ChangeRanking.workloadOfPod(pod)).isEqualTo(workload);
    }

    @Test
    void targetPrefersExplicitWorkloadLabels() {
        assertThat(Target.fromLabels(Map.of("namespace", "shop", "deployment", "checkout", "pod", "other-0")))
                .isEqualTo(new Target("shop", "checkout"));
        assertThat(Target.fromLabels(Map.of("namespace", "shop", "pod", "checkout-7d9f8c6b5-x2k4p")))
                .isEqualTo(new Target("shop", "checkout"));
        assertThat(Target.fromLabels(Map.of("alertname", "HighErrorRate"))).isEqualTo(new Target(null, null));
    }
}

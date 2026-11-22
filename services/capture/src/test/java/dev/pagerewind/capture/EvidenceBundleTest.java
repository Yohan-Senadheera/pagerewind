package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.capture.ChangeEvent.Kind;
import dev.pagerewind.capture.ChangeRanking.Ranked;
import dev.pagerewind.capture.ChangeRanking.Target;
import dev.pagerewind.capture.LogBuffer.ContainerKey;
import dev.pagerewind.capture.LogBuffer.LogLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class EvidenceBundleTest {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
    private static final ContainerKey CHECKOUT = new ContainerKey("shop", "checkout-7d9f8c6b5-x2k4p", "app");
    private static final ContainerKey CHECKOUT_DEAD = new ContainerKey("shop", "checkout-7d9f8c6b5-qwxrz", "app");
    private static final ContainerKey SEARCH = new ContainerKey("shop", "search-5b7c9d8f6-bcdfg", "app");
    private static final ContainerKey OTHER_NS = new ContainerKey("billing", "checkout-7d9f8c6b5-zzzzz", "app");

    static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void containersOfTheAlertingWorkloadAreSelected() {
        Target target = new Target("shop", "checkout");

        assertThat(EvidenceBundle.matches(CHECKOUT, target, null)).isTrue();
        assertThat(EvidenceBundle.matches(CHECKOUT_DEAD, target, null)).isTrue();
        assertThat(EvidenceBundle.matches(SEARCH, target, null)).isFalse();
        assertThat(EvidenceBundle.matches(OTHER_NS, target, null)).isFalse();
        assertThat(EvidenceBundle.matches(SEARCH, new Target("shop", null), null)).as("no workload: whole namespace").isTrue();
        assertThat(EvidenceBundle.matches(SEARCH, new Target(null, "search"), null)).as("no namespace: nothing").isFalse();
        assertThat(EvidenceBundle.matches(SEARCH, new Target("shop", "checkout"), SEARCH.pod())).as("the alert's own pod").isTrue();
    }

    @Test
    void theBundleHoldsReadmeRankedChangesAndLogs() throws IOException {
        ChangeEvent image = ChangeEvent.of(T0.minus(Duration.ofMinutes(4)), Kind.IMAGE, "shop", "checkout", "checkout",
                "Deployment checkout changed image of container app to shop/checkout:1.5", Map.of());
        List<Ranked> ranked = new ChangeRanking(Duration.ofHours(1)).rank(List.of(image), new Target("shop", "checkout"), T0);
        Map<ContainerKey, List<LogLine>> logs = new LinkedHashMap<>();
        logs.put(CHECKOUT_DEAD, List.of(new LogLine(T0.minusSeconds(90), "boom: payment failed")));
        logs.put(CHECKOUT, List.of(new LogLine(T0.minusSeconds(30), "started")));
        UUID incident = UUID.randomUUID();

        EvidenceBundle.Result result = EvidenceBundle.build(incident, T0, new Target("shop", "checkout"), logs, ranked, 5_000_000);
        Map<String, String> files = unzip(result.zip());

        assertThat(result.containers()).isEqualTo(2);
        assertThat(result.logLines()).isEqualTo(2);
        assertThat(result.changes()).isEqualTo(1);
        assertThat(result.likelyCause()).startsWith("Deployment checkout changed image").endsWith("(same workload)");
        assertThat(files).containsKeys("README.txt", "changes.txt",
                "logs/shop/checkout-7d9f8c6b5-qwxrz/app.log", "logs/shop/checkout-7d9f8c6b5-x2k4p/app.log");
        assertThat(files.get("README.txt")).contains(incident.toString()).contains("Likely cause: Deployment checkout");
        assertThat(files.get("changes.txt")).startsWith("1. [score ");
        assertThat(files.get("logs/shop/checkout-7d9f8c6b5-qwxrz/app.log")).isEqualTo(T0.minusSeconds(90) + " boom: payment failed\n");
    }

    @Test
    void overTheLimitTheOldestLinesAreLeftOutAndTheReadmeSaysSo() throws IOException {
        List<LogLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            lines.add(new LogLine(T0.minusSeconds(2000 - i), "line %04d %s".formatted(i, "x".repeat(200))));
        }
        EvidenceBundle.Result result = EvidenceBundle.build(UUID.randomUUID(), T0, new Target("shop", "checkout"),
                Map.of(CHECKOUT, lines), List.of(), 100_000);
        Map<String, String> files = unzip(result.zip());

        assertThat(result.zip().length).isLessThan(100_000);
        assertThat(result.logLines()).isBetween(100, 1999);
        assertThat(files.get("logs/shop/checkout-7d9f8c6b5-x2k4p/app.log")).contains("line 1999").doesNotContain("line 0000");
        assertThat(files.get("README.txt")).contains("older lines were left out");
        assertThat(files.get("changes.txt")).startsWith("No changes recorded");
        assertThat(result.likelyCause()).isNull();
    }
}

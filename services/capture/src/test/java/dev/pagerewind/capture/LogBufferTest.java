package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.capture.LogBuffer.ContainerKey;
import dev.pagerewind.capture.LogBuffer.LogLine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogBufferTest {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
    private static final ContainerKey API = new ContainerKey("shop", "api-7d9f8c6b5-x2k4p", "api");
    private static final ContainerKey WORKER = new ContainerKey("shop", "worker-0", "worker");

    private static LogBuffer buffer(int maxLines, long maxBytes) {
        return new LogBuffer(new Redactor(List.of()), Duration.ofMinutes(15), maxLines, maxBytes, Duration.ofMinutes(30));
    }

    private static List<String> texts(LogBuffer buffer, ContainerKey key) {
        List<LogLine> lines = buffer.snapshot(k -> k.equals(key)).getOrDefault(key, List.of());
        return lines.stream().map(LogLine::text).toList();
    }

    @Test
    void keepsOnlyTheLastWindowOfLines() {
        LogBuffer buffer = buffer(1000, 10_000_000);
        buffer.append(API, T0, "old", T0);
        buffer.append(API, T0.plus(Duration.ofMinutes(10)), "middle", T0.plus(Duration.ofMinutes(10)));
        Instant later = T0.plus(Duration.ofMinutes(20));
        buffer.append(API, later, "new", later);

        assertThat(texts(buffer, API)).containsExactly("middle", "new");
        assertThat(buffer.droppedLines()).as("ageing out is not dropping").isZero();
    }

    @Test
    void keepsAtMostTheLineLimitPerContainer() {
        LogBuffer buffer = buffer(3, 10_000_000);
        for (int i = 1; i <= 5; i++) {
            buffer.append(API, T0.plusSeconds(i), "line " + i, T0.plusSeconds(i));
        }

        assertThat(texts(buffer, API)).containsExactly("line 3", "line 4", "line 5");
        assertThat(buffer.droppedLines()).isEqualTo(2);
    }

    @Test
    void theByteBudgetEvictsTheOldestLineOfAnyContainer() {
        long oneLine = new LogLine(T0, "x".repeat(100)).bytes();
        LogBuffer buffer = buffer(1000, oneLine * 3);
        buffer.append(API, T0.plusSeconds(1), "a".repeat(100), T0.plusSeconds(1));
        buffer.append(WORKER, T0.plusSeconds(2), "b".repeat(100), T0.plusSeconds(2));
        buffer.append(API, T0.plusSeconds(3), "c".repeat(100), T0.plusSeconds(3));
        buffer.append(WORKER, T0.plusSeconds(4), "d".repeat(100), T0.plusSeconds(4));

        assertThat(buffer.totalBytes()).isLessThanOrEqualTo(oneLine * 3);
        assertThat(texts(buffer, API)).containsExactly("c".repeat(100));
        assertThat(texts(buffer, WORKER)).containsExactly("b".repeat(100), "d".repeat(100));
        assertThat(buffer.droppedLines()).isEqualTo(1);
    }

    @Test
    void aDeletedPodKeepsItsLastLinesForTheRetentionPeriod() {
        LogBuffer buffer = buffer(1000, 10_000_000);
        buffer.append(API, T0, "boom", T0);
        buffer.markDeleted("shop", API.pod(), T0.plusSeconds(5));

        // past the 15-minute window, but the pod died: its lines are the evidence
        buffer.sweep(T0.plus(Duration.ofMinutes(25)));
        assertThat(texts(buffer, API)).containsExactly("boom");

        buffer.sweep(T0.plus(Duration.ofMinutes(31)));
        assertThat(texts(buffer, API)).isEmpty();
        assertThat(buffer.containers()).isZero();
        assertThat(buffer.totalBytes()).isZero();
    }

    @Test
    void sweepForgetsLivePodsWithNothingLeftInTheWindow() {
        LogBuffer buffer = buffer(1000, 10_000_000);
        buffer.append(API, T0, "quiet since", T0);

        buffer.sweep(T0.plus(Duration.ofMinutes(16)));

        assertThat(buffer.containers()).isZero();
        assertThat(buffer.totalBytes()).isZero();
    }

    @Test
    void linesAreRedactedBeforeTheyAreStored() {
        LogBuffer buffer = buffer(1000, 10_000_000);
        buffer.append(API, T0, "retrying with password=hunter2", T0);

        assertThat(texts(buffer, API)).containsExactly("retrying with password=[REDACTED]");
    }

    @Test
    void snapshotsAreCopies() {
        LogBuffer buffer = buffer(1000, 10_000_000);
        buffer.append(API, T0, "one", T0);
        List<LogLine> snapshot = buffer.snapshot(k -> true).get(API);
        buffer.append(API, T0.plusSeconds(1), "two", T0.plusSeconds(1));

        assertThat(snapshot).extracting(LogLine::text).containsExactly("one");
    }
}

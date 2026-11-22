package dev.pagerewind.capture;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The flight recorder's memory: for each container, the most recent log lines.
 *
 * <p>Bounded three ways: a time window per container (for example the last 15 minutes), a line count per
 * container, and a byte budget for everything together, which evicts the oldest line across all
 * containers first. When a pod is deleted its lines are kept for a while longer, so evidence from a pod
 * that died before its alert fired is still there when the incident opens.
 *
 * <p>Every line is redacted before it is stored. All methods are thread-safe.
 */
final class LogBuffer {

    record ContainerKey(String namespace, String pod, String container) {
    }

    record LogLine(Instant time, String text) {

        /** Rough heap size: the text plus object overhead. */
        long bytes() {
            return text.getBytes(StandardCharsets.UTF_8).length + 48L;
        }
    }

    private static final class Entry {
        final ArrayDeque<LogLine> lines = new ArrayDeque<>();
        long bytes;
        Instant deletedAt;
    }

    private final Map<ContainerKey, Entry> entries = new HashMap<>();
    private final Redactor redactor;
    private final Duration window;
    private final int maxLinesPerContainer;
    private final long maxTotalBytes;
    private final Duration deletedRetention;
    private long totalBytes;
    private long droppedLines;

    LogBuffer(Redactor redactor, Duration window, int maxLinesPerContainer, long maxTotalBytes, Duration deletedRetention) {
        this.redactor = redactor;
        this.window = window;
        this.maxLinesPerContainer = maxLinesPerContainer;
        this.maxTotalBytes = maxTotalBytes;
        this.deletedRetention = deletedRetention;
    }

    synchronized void append(ContainerKey key, Instant time, String text, Instant now) {
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        entry.deletedAt = null;
        LogLine line = new LogLine(time, redactor.redact(text));
        entry.lines.addLast(line);
        entry.bytes += line.bytes();
        totalBytes += line.bytes();
        while (entry.lines.size() > maxLinesPerContainer) {
            removeFirst(entry, true);
        }
        trimWindow(entry, now);
        while (totalBytes > maxTotalBytes) {
            if (!evictOldest()) {
                break;
            }
        }
    }

    /** The pod is gone: keep its lines for the retention period, then drop them. */
    synchronized void markDeleted(String namespace, String pod, Instant now) {
        for (Map.Entry<ContainerKey, Entry> e : entries.entrySet()) {
            if (e.getKey().namespace().equals(namespace) && e.getKey().pod().equals(pod) && e.getValue().deletedAt == null) {
                e.getValue().deletedAt = now;
            }
        }
    }

    /** Drops lines outside the window and pods deleted longer ago than the retention period. */
    synchronized void sweep(Instant now) {
        Iterator<Map.Entry<ContainerKey, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            boolean expired = entry.deletedAt != null && entry.deletedAt.plus(deletedRetention).isBefore(now);
            if (expired) {
                totalBytes -= entry.bytes;
                it.remove();
                continue;
            }
            // A deleted pod's last lines are the evidence: keep them whole until the retention ends.
            if (entry.deletedAt == null) {
                trimWindow(entry, now);
            }
            if (entry.lines.isEmpty() && entry.deletedAt == null) {
                it.remove();
            }
        }
    }

    /** A copy of the lines of every container whose key matches, oldest line first. */
    synchronized Map<ContainerKey, List<LogLine>> snapshot(Predicate<ContainerKey> filter) {
        Map<ContainerKey, List<LogLine>> copy = new LinkedHashMap<>();
        entries.entrySet().stream()
                .filter(e -> filter.test(e.getKey()))
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ContainerKey::namespace)
                        .thenComparing(ContainerKey::pod).thenComparing(ContainerKey::container)))
                .forEach(e -> copy.put(e.getKey(), new ArrayList<>(e.getValue().lines)));
        return copy;
    }

    synchronized long totalBytes() {
        return totalBytes;
    }

    /** Lines lost to the per-container line limit or the total byte budget. */
    synchronized long droppedLines() {
        return droppedLines;
    }

    synchronized int containers() {
        return entries.size();
    }

    private void trimWindow(Entry entry, Instant now) {
        Instant oldestKept = now.minus(window);
        while (!entry.lines.isEmpty() && entry.lines.peekFirst().time().isBefore(oldestKept)) {
            removeFirst(entry, false);
        }
    }

    /** Removes the oldest line of all containers; false if there is nothing left to remove. */
    private boolean evictOldest() {
        Entry oldest = null;
        for (Entry entry : entries.values()) {
            if (!entry.lines.isEmpty()
                    && (oldest == null || entry.lines.peekFirst().time().isBefore(oldest.lines.peekFirst().time()))) {
                oldest = entry;
            }
        }
        if (oldest == null) {
            return false;
        }
        removeFirst(oldest, true);
        return true;
    }

    /** {@code dropped}: removed because of a limit, not because it aged out of the window. */
    private void removeFirst(Entry entry, boolean dropped) {
        LogLine removed = entry.lines.removeFirst();
        entry.bytes -= removed.bytes();
        totalBytes -= removed.bytes();
        if (dropped) {
            droppedLines++;
        }
    }
}

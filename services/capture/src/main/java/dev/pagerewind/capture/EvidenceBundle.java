package dev.pagerewind.capture;

import dev.pagerewind.capture.ChangeRanking.Ranked;
import dev.pagerewind.capture.ChangeRanking.Target;
import dev.pagerewind.capture.LogBuffer.ContainerKey;
import dev.pagerewind.capture.LogBuffer.LogLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip attached to an incident: {@code README.txt}, {@code changes.txt} (ranked, likely cause first)
 * and {@code logs/<namespace>/<pod>/<container>.log}. If the logs would exceed the size limit, the oldest
 * lines are left out first and the README says so.
 */
final class EvidenceBundle {

    record Result(byte[] zip, int containers, int logLines, int changes, String likelyCause) {
    }

    private EvidenceBundle() {
    }

    /** The containers whose logs belong in the bundle: the alert's workload or pod, else its whole namespace. */
    static boolean matches(ContainerKey key, Target target, String pod) {
        if (target.namespace() == null || !target.namespace().equals(key.namespace())) {
            return false;
        }
        if (pod != null && pod.equals(key.pod())) {
            return true;
        }
        if (target.workload() == null) {
            return true;
        }
        return target.workload().equals(ChangeRanking.workloadOfPod(key.pod())) || target.workload().equals(key.pod());
    }

    static Result build(UUID incidentId, Instant at, Target target, Map<ContainerKey, List<LogLine>> logs,
            List<Ranked> changes, long maxBytes) {
        // Budget for log text: the limit minus room for the README, the change list and zip overhead.
        long changesBytes = changes.stream().mapToLong(r -> r.reason().length() + 32L).sum();
        long logBudget = Math.max(0, maxBytes - changesBytes - 4096);
        Map<ContainerKey, List<LogLine>> kept = newestWithin(logs, logBudget);
        int keptLines = kept.values().stream().mapToInt(List::size).sum();
        int allLines = logs.values().stream().mapToInt(List::size).sum();
        String likelyCause = changes.isEmpty() ? null : changes.getFirst().reason();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "README.txt", readme(incidentId, at, target, kept.size(), keptLines, allLines - keptLines,
                    changes.size(), likelyCause));
            add(zip, "changes.txt", changesText(changes));
            for (Map.Entry<ContainerKey, List<LogLine>> entry : kept.entrySet()) {
                ContainerKey key = entry.getKey();
                StringBuilder text = new StringBuilder();
                for (LogLine line : entry.getValue()) {
                    text.append(line.time()).append(' ').append(line.text()).append('\n');
                }
                add(zip, "logs/%s/%s/%s.log".formatted(key.namespace(), key.pod(), key.container()), text.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Result(bytes.toByteArray(), kept.size(), keptLines, changes.size(), likelyCause);
    }

    /** Keeps the newest lines of every container, across containers, until the byte budget is used. */
    private static Map<ContainerKey, List<LogLine>> newestWithin(Map<ContainerKey, List<LogLine>> logs, long budget) {
        record Candidate(ContainerKey key, LogLine line) {
        }
        List<Candidate> all = new ArrayList<>();
        logs.forEach((key, lines) -> lines.forEach(line -> all.add(new Candidate(key, line))));
        all.sort((a, b) -> b.line().time().compareTo(a.line().time()));
        long used = 0;
        Map<ContainerKey, List<LogLine>> kept = new LinkedHashMap<>();
        logs.keySet().forEach(key -> kept.put(key, new ArrayList<>()));
        List<Candidate> chosen = new ArrayList<>();
        for (Candidate candidate : all) {
            long size = candidate.line().text().getBytes(StandardCharsets.UTF_8).length + 40L;
            if (used + size > budget) {
                break;
            }
            used += size;
            chosen.add(candidate);
        }
        chosen.sort((a, b) -> a.line().time().compareTo(b.line().time()));
        chosen.forEach(c -> kept.get(c.key()).add(c.line()));
        kept.values().removeIf(List::isEmpty);
        return kept;
    }

    private static String readme(UUID incidentId, Instant at, Target target, int containers, int lines, int leftOut,
            int changes, String likelyCause) {
        StringBuilder text = new StringBuilder();
        text.append("PageRewind evidence for incident ").append(incidentId).append('\n');
        text.append("Captured at ").append(at).append('\n');
        text.append("Alert target: namespace ").append(target.namespace() == null ? "(none)" : target.namespace())
                .append(", workload ").append(target.workload() == null ? "(any)" : target.workload()).append("\n\n");
        text.append("Likely cause: ").append(likelyCause == null ? "no changes in the hour before the alert" : likelyCause)
                .append("\n\n");
        text.append("Logs: ").append(lines).append(" lines from ").append(containers).append(" containers (redacted).\n");
        if (leftOut > 0) {
            text.append(leftOut).append(" older lines were left out to keep the bundle under its size limit.\n");
        }
        text.append("Changes: ").append(changes).append(" in the hour before the alert, ranked in changes.txt.\n");
        return text.toString();
    }

    private static String changesText(List<Ranked> changes) {
        if (changes.isEmpty()) {
            return "No changes recorded in the hour before the alert.\n";
        }
        StringBuilder text = new StringBuilder();
        int rank = 1;
        for (Ranked ranked : changes) {
            text.append(rank++).append(". [score ").append(ranked.score()).append("] ")
                    .append(ranked.change().occurredAt()).append("  ").append(ranked.reason()).append('\n');
        }
        return text.toString();
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

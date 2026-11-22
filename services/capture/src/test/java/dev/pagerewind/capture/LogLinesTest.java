package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogLinesTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void kubernetesTimestampsAreParsed() {
        List<String> seen = new ArrayList<>();
        LogLines.parse("2026-11-23T09:59:58.123456789Z checkout failed", () -> NOW, (t, text) -> seen.add(t + "|" + text));
        LogLines.parse("no timestamp here", () -> NOW, (t, text) -> seen.add(t + "|" + text));

        assertThat(seen).containsExactly("2026-11-23T09:59:58.123456789Z|checkout failed", NOW + "|no timestamp here");
    }

    @Test
    void theStreamIsSplitIntoLinesAcrossWrites() throws Exception {
        List<String> lines = new ArrayList<>();
        try (OutputStream out = LogLines.splitting(lines::add)) {
            out.write("first li".getBytes(StandardCharsets.UTF_8));
            out.write("ne\r\nsecond\nünïcödé ✓".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(lines).containsExactly("first line", "second", "ünïcödé ✓");
    }

    @Test
    void runawayLinesAreCut() throws Exception {
        List<String> lines = new ArrayList<>();
        try (OutputStream out = LogLines.splitting(lines::add)) {
            out.write(("x".repeat(LogLines.MAX_LINE_BYTES + 500) + "\nnext\n").getBytes(StandardCharsets.UTF_8));
        }

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).hasSize(LogLines.MAX_LINE_BYTES + " [line cut]".length()).endsWith(" [line cut]");
        assertThat(lines.get(1)).isEqualTo("next");
    }
}

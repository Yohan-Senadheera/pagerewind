package dev.pagerewind.capture;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Turns the byte stream of a followed container log into timestamped lines. Kubernetes prefixes each line
 * with its RFC 3339 time when asked ({@code timestamps=true}).
 */
final class LogLines {

    /** Very long lines are cut, so one runaway line cannot fill the buffer. */
    static final int MAX_LINE_BYTES = 8 * 1024;

    private LogLines() {
    }

    /** Splits "2026-11-23T01:02:03.123456789Z message" into its time and text; lines without a time use now. */
    static void parse(String raw, Supplier<Instant> now, BiConsumer<Instant, String> sink) {
        int space = raw.indexOf(' ');
        if (space > 0) {
            try {
                Instant time = Instant.parse(raw.substring(0, space));
                sink.accept(time, raw.substring(space + 1));
                return;
            } catch (DateTimeParseException e) {
                // not a timestamp prefix
            }
        }
        sink.accept(now.get(), raw);
    }

    /** An output stream that calls {@code onLine} for every complete line written to it. */
    static OutputStream splitting(java.util.function.Consumer<String> onLine) {
        return new OutputStream() {
            private final ByteArrayOutputStream current = new ByteArrayOutputStream();
            private boolean truncated;

            @Override
            public synchronized void write(int b) {
                if (b == '\n') {
                    flushLine();
                } else if (current.size() < MAX_LINE_BYTES) {
                    current.write(b);
                } else {
                    truncated = true;
                }
            }

            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                for (int i = offset; i < offset + length; i++) {
                    write(bytes[i]);
                }
            }

            @Override
            public synchronized void close() {
                if (current.size() > 0) {
                    flushLine();
                }
            }

            private void flushLine() {
                String line = current.toString(StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                onLine.accept(truncated ? line + " [line cut]" : line);
                current.reset();
                truncated = false;
            }
        };
    }
}

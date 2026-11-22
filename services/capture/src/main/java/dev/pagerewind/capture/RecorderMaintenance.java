package dev.pagerewind.capture;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ages out buffered lines and old changes, and publishes the recorder's size as metrics. */
@Component
class RecorderMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RecorderMaintenance.class);

    private final LogBuffer buffer;
    private final ChangeRepository changes;
    private final CaptureProperties properties;
    private final Clock clock;

    RecorderMaintenance(LogBuffer buffer, ChangeRepository changes, CaptureProperties properties, Clock clock,
            PodLogFollower follower, MeterRegistry meters) {
        this.buffer = buffer;
        this.changes = changes;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("pagerewind.recorder.buffer.bytes", buffer, LogBuffer::totalBytes)
                .description("Bytes of log lines held by the flight recorder").baseUnit("bytes").register(meters);
        Gauge.builder("pagerewind.recorder.containers", buffer, LogBuffer::containers)
                .description("Containers with lines in the flight recorder").register(meters);
        Gauge.builder("pagerewind.recorder.followed.containers", follower, PodLogFollower::followedContainers)
                .description("Container log streams being followed").register(meters);
        FunctionCounter.builder("pagerewind.recorder.dropped.lines", buffer, LogBuffer::droppedLines)
                .description("Lines dropped because of the per-container or total size limit").register(meters);
    }

    @Scheduled(fixedDelayString = "${pagerewind.capture.sweep-interval:30s}")
    void sweepBuffer() {
        buffer.sweep(clock.instant());
    }

    @Scheduled(fixedDelayString = "${pagerewind.capture.retention-interval:1h}")
    void deleteOldChanges() {
        int deleted = changes.deleteOlderThan(clock.instant().minus(properties.changeRetention()));
        if (deleted > 0) {
            log.info("Deleted {} changes older than {}", deleted, properties.changeRetention());
        }
    }
}

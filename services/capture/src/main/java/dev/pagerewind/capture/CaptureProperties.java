package dev.pagerewind.capture;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param recordLabel          namespaces opt in with this label set to "true"; nothing else is recorded
 * @param window               how far back the flight recorder keeps logs of a running container
 * @param maxLinesPerContainer line limit per container
 * @param maxBufferSize        memory budget for all buffered lines together
 * @param deletedPodRetention  how long the lines of a deleted pod are kept
 * @param changeRetention      how long change events are kept in the database
 * @param lookBack             how far before an alert changes are considered
 * @param redactPatterns       extra regular expressions to mask, on top of the built-in ones
 * @param followLogs           follow container logs (off in tests that only check changes)
 * @param recordChanges        watch for changes
 * @param maxBundleSize        size limit of one incident's evidence bundle
 * @param evidenceRetention    how long evidence bundles are kept
 */
@ConfigurationProperties("pagerewind.capture")
record CaptureProperties(String recordLabel, Duration window, int maxLinesPerContainer, DataSize maxBufferSize,
        Duration deletedPodRetention, Duration changeRetention, Duration lookBack, List<String> redactPatterns,
        boolean followLogs, boolean recordChanges, DataSize maxBundleSize, Duration evidenceRetention) {

    CaptureProperties {
        recordLabel = recordLabel == null ? "pagerewind.dev/record" : recordLabel;
        window = window == null ? Duration.ofMinutes(15) : window;
        maxLinesPerContainer = maxLinesPerContainer <= 0 ? 2000 : maxLinesPerContainer;
        maxBufferSize = maxBufferSize == null ? DataSize.ofMegabytes(256) : maxBufferSize;
        deletedPodRetention = deletedPodRetention == null ? Duration.ofMinutes(30) : deletedPodRetention;
        changeRetention = changeRetention == null ? Duration.ofDays(7) : changeRetention;
        lookBack = lookBack == null ? Duration.ofHours(1) : lookBack;
        redactPatterns = redactPatterns == null ? List.of() : List.copyOf(redactPatterns);
        maxBundleSize = maxBundleSize == null ? DataSize.ofMegabytes(5) : maxBundleSize;
        evidenceRetention = evidenceRetention == null ? Duration.ofDays(30) : evidenceRetention;
    }
}

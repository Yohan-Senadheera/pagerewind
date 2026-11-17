package dev.pagerewind.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook body (version 4). Only the fields PageRewind uses are mapped.
 *
 * @see <a href="https://prometheus.io/docs/alerting/latest/configuration/#webhook_config">webhook_config</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(
        String version,
        String status,
        String receiver,
        Map<String, String> commonLabels,
        String externalURL,
        List<Alert> alerts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            Instant endsAt,
            String generatorURL,
            String fingerprint) {
    }
}

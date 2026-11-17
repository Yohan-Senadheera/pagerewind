package dev.pagerewind.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body for {@code POST /v1/alerts/generic}, for tools that are not Alertmanager.
 *
 * @param dedupKey optional; alerts with the same key belong to the same incident. Without it, the
 *                 key is derived from the service and labels
 * @param status   "firing" (default) or "resolved"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenericAlertRequest(
        @NotBlank @Size(max = 200) String service,
        @Size(max = 50) String severity,
        @NotBlank @Size(max = 2000) String summary,
        @Size(max = 100) Map<String, String> labels,
        @Size(max = 200) String dedupKey,
        @Pattern(regexp = "(?i)firing|resolved") String status) {
}

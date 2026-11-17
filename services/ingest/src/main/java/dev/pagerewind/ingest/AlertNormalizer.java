package dev.pagerewind.ingest;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.Severity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns alerts from each source into {@link AlertReceived} events.
 *
 * <p>The {@code repeat} flag is always false here; the duplicate filter sets it later.
 */
@Component
public class AlertNormalizer {

    static final String ALERTMANAGER = "alertmanager";
    static final String GENERIC = "generic";
    static final int MAX_SUMMARY_LENGTH = 500;

    private static final List<String> SERVICE_LABELS = List.of("service", "app", "app_kubernetes_io_name", "job", "namespace");

    private final Clock clock;

    public AlertNormalizer(Clock clock) {
        this.clock = clock;
    }

    public List<AlertReceived> fromAlertmanager(AlertmanagerPayload payload) {
        Map<String, String> common = payload.commonLabels() == null ? Map.of() : payload.commonLabels();
        Instant now = clock.instant();
        return payload.alerts().stream().map(alert -> {
            Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
            Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();
            String fingerprint = isBlank(alert.fingerprint())
                    ? hash(new TreeMap<>(labels).toString())
                    : alert.fingerprint().trim();
            return new AlertReceived(
                    UUID.randomUUID(),
                    now,
                    AlertReceived.CURRENT_VERSION,
                    ALERTMANAGER,
                    serviceOf(labels, common),
                    Severity.parse(labels.get("severity")),
                    AlertStatus.parse(alert.status() != null ? alert.status() : payload.status()),
                    summaryOf(annotations, labels),
                    labels,
                    ALERTMANAGER + ":" + fingerprint,
                    false,
                    alert.startsAt() != null ? alert.startsAt() : now);
        }).toList();
    }

    public AlertReceived fromGeneric(GenericAlertRequest request) {
        Map<String, String> labels = request.labels() == null ? Map.of() : request.labels();
        String service = request.service().trim();
        String key = isBlank(request.dedupKey())
                ? hash(service + "\n" + new TreeMap<>(labels) + (labels.isEmpty() ? "\n" + request.summary().trim() : ""))
                : request.dedupKey().trim();
        Instant now = clock.instant();
        return new AlertReceived(
                UUID.randomUUID(),
                now,
                AlertReceived.CURRENT_VERSION,
                GENERIC,
                service,
                Severity.parse(request.severity()),
                AlertStatus.parse(request.status()),
                truncate(request.summary().trim()),
                labels,
                GENERIC + ":" + key,
                false,
                now);
    }

    private static String serviceOf(Map<String, String> labels, Map<String, String> common) {
        for (String name : SERVICE_LABELS) {
            if (!isBlank(labels.get(name))) {
                return labels.get(name).trim();
            }
            if (!isBlank(common.get(name))) {
                return common.get(name).trim();
            }
        }
        return "unknown";
    }

    private static String summaryOf(Map<String, String> annotations, Map<String, String> labels) {
        for (String candidate : new String[] {annotations.get("summary"), annotations.get("description"), labels.get("alertname")}) {
            if (!isBlank(candidate)) {
                return truncate(candidate.trim());
            }
        }
        return "Alert";
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SUMMARY_LENGTH ? text : text.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

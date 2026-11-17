package dev.pagerewind.ingest;

import dev.pagerewind.events.AlertReceived;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/alerts")
public class AlertController {

    static final String SIGNATURE_HEADER = "X-PageRewind-Signature";

    private final RequestAuthenticator authenticator;
    private final AlertNormalizer normalizer;
    private final DuplicateFilter duplicateFilter;
    private final EventPublisher publisher;
    private final JsonMapper json;
    private final Validator validator;
    private final IngestProperties properties;
    private final MeterRegistry meters;

    public AlertController(RequestAuthenticator authenticator, AlertNormalizer normalizer,
            DuplicateFilter duplicateFilter, EventPublisher publisher, JsonMapper json, Validator validator,
            IngestProperties properties, MeterRegistry meters) {
        this.authenticator = authenticator;
        this.normalizer = normalizer;
        this.duplicateFilter = duplicateFilter;
        this.publisher = publisher;
        this.json = json;
        this.validator = validator;
        this.properties = properties;
        this.meters = meters;
    }

    /** Alertmanager webhook receiver. Point a {@code webhook_config} at this URL. */
    @PostMapping("/alertmanager")
    public ResponseEntity<IngestResponse> alertmanager(@RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        String source = AlertNormalizer.ALERTMANAGER;
        checkRequest(source, body, authorization, signature);

        AlertmanagerPayload payload = parse(source, body, AlertmanagerPayload.class);
        if (payload.alerts() == null || payload.alerts().isEmpty()) {
            throw reject(source, "invalid", new ApiExceptions.BadRequest("The payload has no alerts"));
        }
        if (payload.alerts().size() > properties.maxAlerts()) {
            throw reject(source, "too_many_alerts", new ApiExceptions.BadRequest(
                    "At most " + properties.maxAlerts() + " alerts are accepted per request"));
        }
        return accept(normalizer.fromAlertmanager(payload));
    }

    /** Simple JSON alert for tools that are not Alertmanager. */
    @PostMapping("/generic")
    public ResponseEntity<IngestResponse> generic(@RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        String source = AlertNormalizer.GENERIC;
        checkRequest(source, body, authorization, signature);

        GenericAlertRequest request = parse(source, body, GenericAlertRequest.class);
        Set<ConstraintViolation<GenericAlertRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw reject(source, "invalid", new ApiExceptions.BadRequest(detail));
        }
        return accept(List.of(normalizer.fromGeneric(request)));
    }

    private void checkRequest(String source, byte[] body, String authorization, String signature) {
        if (body.length > properties.maxBodyBytes()) {
            throw reject(source, "too_large", new ApiExceptions.PayloadTooLarge(properties.maxBodyBytes()));
        }
        if (!authenticator.isAuthentic(source, body, authorization, signature)) {
            throw reject(source, "unauthenticated", new ApiExceptions.Unauthenticated());
        }
    }

    private <T> T parse(String source, byte[] body, Class<T> type) {
        try {
            T value = json.readValue(body, type);
            if (value == null) {
                throw new ApiExceptions.BadRequest("The body is empty");
            }
            return value;
        } catch (JacksonException e) {
            throw reject(source, "malformed", new ApiExceptions.BadRequest("The body is not valid JSON for this endpoint"));
        }
    }

    private ResponseEntity<IngestResponse> accept(List<AlertReceived> alerts) {
        List<AlertReceived> marked = alerts.stream().map(duplicateFilter::mark).toList();
        publisher.publishAll(marked);
        for (AlertReceived alert : marked) {
            Counter.builder("pagerewind.alerts.received")
                    .description("Alerts accepted and published")
                    .tag("source", alert.source())
                    .tag("status", alert.status().name().toLowerCase())
                    .tag("repeat", Boolean.toString(alert.repeat()))
                    .register(meters)
                    .increment();
        }
        List<UUID> ids = marked.stream().map(AlertReceived::eventId).toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestResponse(ids.size(), ids));
    }

    private RuntimeException reject(String source, String reason, RuntimeException error) {
        Counter.builder("pagerewind.alerts.rejected")
                .description("Alert requests that were rejected")
                .tag("source", source)
                .tag("reason", reason)
                .register(meters)
                .increment();
        return error;
    }

    public record IngestResponse(int accepted, List<UUID> eventIds) {
    }
}

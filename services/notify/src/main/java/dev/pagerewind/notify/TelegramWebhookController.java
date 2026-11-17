package dev.pagerewind.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives button presses from Telegram ({@code setWebhook} with a {@code secret_token}).
 *
 * <p>A press is only accepted from a chat that PageRewind actually paged for that incident, and the
 * action is recorded under that person's name. Telegram retries any non-2xx response, so after the
 * secret check every update gets {@code 200}.
 */
@RestController
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(@JsonProperty("update_id") long updateId, @JsonProperty("callback_query") CallbackQuery callbackQuery) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, CallbackMessage message, String data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackMessage(Chat chat) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {
    }

    private final NotifyProperties properties;
    private final DeliveryRepository deliveries;
    private final IncidentClient incidents;
    private final TelegramClient telegram;
    private final MeterRegistry meters;

    public TelegramWebhookController(NotifyProperties properties, DeliveryRepository deliveries,
            IncidentClient incidents, TelegramClient telegram, MeterRegistry meters) {
        this.properties = properties;
        this.deliveries = deliveries;
        this.incidents = incidents;
        this.telegram = telegram;
        this.meters = meters;
    }

    @PostMapping("/v1/telegram/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody Update update) {
        String expected = properties.telegram().webhookSecret();
        if (expected.isBlank() || secret == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        CallbackQuery query = update.callbackQuery();
        if (query == null || query.data() == null) {
            return ResponseEntity.ok().build();
        }
        String reply = handle(query);
        try {
            telegram.answerCallback(query.id(), reply);
        } catch (RuntimeException e) {
            log.warn("Could not answer Telegram callback {}: {}", query.id(), e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private String handle(CallbackQuery query) {
        ActionTokens.Action action;
        String rest;
        if (query.data().startsWith(PageMessages.ACK_PREFIX)) {
            action = ActionTokens.Action.ACKNOWLEDGE;
            rest = query.data().substring(PageMessages.ACK_PREFIX.length());
        } else if (query.data().startsWith(PageMessages.RESOLVE_PREFIX)) {
            action = ActionTokens.Action.RESOLVE;
            rest = query.data().substring(PageMessages.RESOLVE_PREFIX.length());
        } else {
            return count("unknown", "Unknown button.");
        }
        UUID incidentId;
        try {
            incidentId = UUID.fromString(rest);
        } catch (IllegalArgumentException e) {
            return count("unknown", "Unknown button.");
        }
        String chatId = Long.toString(query.message() != null ? query.message().chat().id() : query.from().id());
        Optional<String> person = deliveries.personPagedInChat(incidentId, chatId);
        if (person.isEmpty()) {
            log.warn("Telegram chat {} pressed {} for incident {} but was never paged for it", chatId, action, incidentId);
            return count("rejected", "You were not paged for this incident.");
        }
        IncidentClient.Result result = incidents.apply(incidentId, action, person.get());
        return count(result.name().toLowerCase(), IncidentClient.describe(result, action));
    }

    private String count(String outcome, String reply) {
        Counter.builder("pagerewind.notify.telegram.actions").description("Telegram button presses by outcome")
                .tag("outcome", outcome).register(meters).increment();
        return reply;
    }
}

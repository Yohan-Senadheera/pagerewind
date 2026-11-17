package dev.pagerewind.notify;

import dev.pagerewind.notify.Retrier.PermanentFailure;
import dev.pagerewind.notify.Retrier.TransientFailure;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The two Telegram Bot API calls PageRewind needs. */
@Component
public class TelegramClient {

    public record Button(String text, String callbackData) {
    }

    private final NotifyProperties.Telegram settings;
    private final RestClient rest;
    private final JsonMapper json;

    public TelegramClient(NotifyProperties properties, RestClient.Builder builder, JsonMapper json) {
        this.settings = properties.telegram();
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.timeout());
        factory.setReadTimeout(settings.timeout());
        this.rest = builder.baseUrl(settings.apiBaseUrl() + "/bot" + settings.botToken()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public void sendMessage(String chatId, String text, List<Button> buttons) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "reply_markup", Map.of("inline_keyboard",
                        List.of(buttons.stream().map(b -> Map.of("text", b.text(), "callback_data", b.callbackData())).toList())));
        call("sendMessage", body);
    }

    public void answerCallback(String callbackQueryId, String text) {
        call("answerCallbackQuery", Map.of("callback_query_id", callbackQueryId, "text", text));
    }

    private void call(String method, Map<String, Object> body) {
        try {
            rest.post().uri("/" + method).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            String description = "Telegram " + method + " returned " + e.getStatusCode().value();
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new TransientFailure(description, retryAfter(e.getResponseBodyAsString()));
            }
            if (e.getStatusCode().is5xxServerError()) {
                throw new TransientFailure(description);
            }
            throw new PermanentFailure(description + ": " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new TransientFailure("Telegram " + method + " failed: " + e.getMessage());
        }
    }

    private Duration retryAfter(String body) {
        try {
            Object seconds = json.readValue(body, Map.class).get("parameters") instanceof Map<?, ?> p ? p.get("retry_after") : null;
            return seconds instanceof Number n ? Duration.ofSeconds(n.longValue()) : Duration.ZERO;
        } catch (RuntimeException e) {
            return Duration.ZERO;
        }
    }
}

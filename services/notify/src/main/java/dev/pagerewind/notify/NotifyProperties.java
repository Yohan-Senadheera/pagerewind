package dev.pagerewind.notify;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pagesQueue      queue for PageRequested events
 * @param deliveryLimit   redeliveries before a failing message goes to the dead-letter queue
 * @param publicBaseUrl   public HTTPS address of this service, used in email links
 * @param incidentBaseUrl internal address of the incident service
 * @param rateLimit       a person is paged at most once per incident and channel in this window
 */
@ConfigurationProperties("pagerewind.notify")
public record NotifyProperties(
        @DefaultValue("notify.pages") String pagesQueue,
        @DefaultValue("4") int deliveryLimit,
        @DefaultValue("http://localhost:8084") String publicBaseUrl,
        @DefaultValue("http://localhost:8082") String incidentBaseUrl,
        @DefaultValue("2m") Duration rateLimit,
        @DefaultValue Retry retry,
        @DefaultValue ActionLinks actionLinks,
        @DefaultValue Telegram telegram,
        @DefaultValue Email email) {

    public String pagesDeadLetterQueue() {
        return pagesQueue + ".dlq";
    }

    /**
     * @param maxAttempts    tries per contact, including the first
     * @param initialBackoff upper bound of the first random wait
     * @param maxBackoff     upper bound of any wait
     */
    public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue("200ms") Duration initialBackoff,
            @DefaultValue("2s") Duration maxBackoff) {
    }

    /**
     * @param secret HMAC key for email action links; links are rejected while it is empty
     * @param ttl    how long a link works
     */
    public record ActionLinks(@DefaultValue("") String secret, @DefaultValue("30m") Duration ttl) {
    }

    /**
     * @param botToken      from BotFather; Telegram is off while it is empty
     * @param webhookSecret sent by Telegram in X-Telegram-Bot-Api-Secret-Token
     * @param apiBaseUrl    Telegram Bot API address
     */
    public record Telegram(@DefaultValue("") String botToken, @DefaultValue("") String webhookSecret,
            @DefaultValue("https://api.telegram.org") String apiBaseUrl, @DefaultValue("5s") Duration timeout) {

        boolean enabled() {
            return !botToken.isBlank();
        }
    }

    public record Email(@DefaultValue("pagerewind@localhost") String from) {
    }
}

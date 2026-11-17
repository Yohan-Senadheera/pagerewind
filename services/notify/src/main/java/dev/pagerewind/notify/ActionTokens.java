package dev.pagerewind.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signed links that let a person acknowledge or resolve an incident from an email.
 *
 * <p>Token = base64url(payload) "." base64url(HMAC-SHA256(payload)). The payload holds the
 * incident, the action, the person, an expiry and a random nonce. The nonce is stored when the link
 * is used, so each link works once.
 */
@Component
public class ActionTokens {

    public enum Action { ACKNOWLEDGE, RESOLVE }

    public record Claims(UUID incidentId, Action action, String personId, Instant expiresAt, String nonce) {
    }

    public static class InvalidToken extends RuntimeException {
        InvalidToken(String message) {
            super(message);
        }
    }

    public static class ExpiredToken extends RuntimeException {
        ExpiredToken() {
            super("This link has expired");
        }
    }

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final NotifyProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ActionTokens(NotifyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return !properties.actionLinks().secret().isBlank();
    }

    public String create(UUID incidentId, Action action, String personId) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        Instant expires = clock.instant().plus(properties.actionLinks().ttl());
        String payload = String.join("|", VERSION, incidentId.toString(), action.name(), personId,
                Long.toString(expires.getEpochSecond()), ENCODER.encodeToString(nonce));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(sign(payloadBytes));
    }

    public Claims verify(String token) {
        if (!enabled()) {
            throw new InvalidToken("Action links are not configured");
        }
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            throw new InvalidToken("Malformed link");
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = DECODER.decode(token.substring(0, dot));
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidToken("Malformed link");
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), signature)) {
            throw new InvalidToken("This link is not valid");
        }
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new InvalidToken("Malformed link");
        }
        Claims claims;
        try {
            claims = new Claims(UUID.fromString(parts[1]), Action.valueOf(parts[2]), parts[3],
                    Instant.ofEpochSecond(Long.parseLong(parts[4])), parts[5]);
        } catch (IllegalArgumentException e) {
            throw new InvalidToken("Malformed link");
        }
        if (!clock.instant().isBefore(claims.expiresAt())) {
            throw new ExpiredToken();
        }
        return claims;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.actionLinks().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}

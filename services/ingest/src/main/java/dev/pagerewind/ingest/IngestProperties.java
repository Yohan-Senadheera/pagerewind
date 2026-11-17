package dev.pagerewind.ingest;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the ingest service.
 *
 * @param sources         credentials per alert source ("alertmanager", "generic"). A source with no
 *                        token and no HMAC secret rejects every request
 * @param duplicateWindow how long an alert fingerprint counts as "recently seen"
 * @param publishTimeout  how long to wait for RabbitMQ to confirm a message
 * @param maxBodyBytes    largest accepted request body
 * @param maxAlerts       most alerts accepted in one request
 */
@ConfigurationProperties("pagerewind.ingest")
public record IngestProperties(
        Map<String, SourceCredentials> sources,
        @DefaultValue("5m") Duration duplicateWindow,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("1048576") int maxBodyBytes,
        @DefaultValue("1000") int maxAlerts) {

    public IngestProperties {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public SourceCredentials credentialsFor(String source) {
        return sources.getOrDefault(source, SourceCredentials.NONE);
    }

    /**
     * @param token      shared bearer token, sent as {@code Authorization: Bearer <token>}.
     *                   Alertmanager supports this natively
     * @param hmacSecret secret for {@code X-PageRewind-Signature: sha256=<hex HMAC of the body>}
     */
    public record SourceCredentials(String token, String hmacSecret) {

        static final SourceCredentials NONE = new SourceCredentials(null, null);

        boolean hasToken() {
            return token != null && !token.isBlank();
        }

        boolean hasHmacSecret() {
            return hmacSecret != null && !hmacSecret.isBlank();
        }
    }
}

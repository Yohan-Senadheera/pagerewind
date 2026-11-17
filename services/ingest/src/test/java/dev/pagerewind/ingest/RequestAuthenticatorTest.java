package dev.pagerewind.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.ingest.IngestProperties.SourceCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestAuthenticatorTest {

    private static final String TOKEN = UUID.randomUUID().toString();
    private static final String SECRET = UUID.randomUUID().toString();
    private static final byte[] BODY = "{\"alerts\":[]}".getBytes(StandardCharsets.UTF_8);

    private final RequestAuthenticator auth = new RequestAuthenticator(new IngestProperties(
            Map.of(
                    "both", new SourceCredentials(TOKEN, SECRET),
                    "hmac-only", new SourceCredentials(null, SECRET),
                    "token-only", new SourceCredentials(TOKEN, ""),
                    "none", new SourceCredentials("", " ")),
            Duration.ofMinutes(5), Duration.ofSeconds(5), 1024, 10));

    @Test
    void acceptsValidBearerToken() {
        assertThat(auth.isAuthentic("both", BODY, "Bearer " + TOKEN, null)).isTrue();
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        assertThat(auth.isAuthentic("both", BODY, "bearer " + TOKEN, null)).isTrue();
    }

    @Test
    void rejectsWrongToken() {
        assertThat(auth.isAuthentic("both", BODY, "Bearer " + TOKEN + "x", null)).isFalse();
        assertThat(auth.isAuthentic("both", BODY, "Bearer " + TOKEN.substring(1), null)).isFalse();
    }

    @Test
    void rejectsOtherSchemes() {
        assertThat(auth.isAuthentic("both", BODY, "Basic " + TOKEN, null)).isFalse();
        assertThat(auth.isAuthentic("both", BODY, TOKEN, null)).isFalse();
    }

    @Test
    void acceptsValidSignature() {
        assertThat(auth.isAuthentic("both", BODY, null, RequestAuthenticator.sign(SECRET, BODY))).isTrue();
    }

    @Test
    void acceptsUppercaseHexSignature() {
        String signature = RequestAuthenticator.sign(SECRET, BODY);
        String upper = "sha256=" + signature.substring(7).toUpperCase(Locale.ROOT);
        assertThat(auth.isAuthentic("hmac-only", BODY, null, upper)).isTrue();
    }

    @Test
    void rejectsSignatureOfDifferentBody() {
        byte[] tampered = "{\"alerts\":[1]}".getBytes(StandardCharsets.UTF_8);
        assertThat(auth.isAuthentic("both", tampered, null, RequestAuthenticator.sign(SECRET, BODY))).isFalse();
    }

    @Test
    void rejectsSignatureWithWrongSecret() {
        assertThat(auth.isAuthentic("both", BODY, null, RequestAuthenticator.sign(UUID.randomUUID().toString(), BODY))).isFalse();
    }

    @Test
    void rejectsMalformedSignatures() {
        assertThat(auth.isAuthentic("both", BODY, null, "sha256=not-hex")).isFalse();
        assertThat(auth.isAuthentic("both", BODY, null, "sha256=")).isFalse();
        assertThat(auth.isAuthentic("both", BODY, null, RequestAuthenticator.sign(SECRET, BODY).substring(7))).isFalse();
        assertThat(auth.isAuthentic("both", BODY, null, "sha1=abcd")).isFalse();
    }

    @Test
    void rejectsMissingCredentials() {
        assertThat(auth.isAuthentic("both", BODY, null, null)).isFalse();
    }

    @Test
    void sourceOnlyAcceptsMethodsThatAreConfigured() {
        assertThat(auth.isAuthentic("hmac-only", BODY, "Bearer " + TOKEN, null)).isFalse();
        assertThat(auth.isAuthentic("token-only", BODY, null, RequestAuthenticator.sign(SECRET, BODY))).isFalse();
        assertThat(auth.isAuthentic("token-only", BODY, "Bearer " + TOKEN, null)).isTrue();
    }

    @Test
    void unconfiguredOrBlankSourceRejectsEverything() {
        assertThat(auth.isAuthentic("none", BODY, "Bearer ", RequestAuthenticator.sign(" ", BODY))).isFalse();
        assertThat(auth.isAuthentic("unknown", BODY, "Bearer " + TOKEN, RequestAuthenticator.sign(SECRET, BODY))).isFalse();
    }
}

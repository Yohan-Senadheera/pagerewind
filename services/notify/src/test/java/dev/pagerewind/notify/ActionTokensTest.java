package dev.pagerewind.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final UUID INCIDENT = UUID.fromString("7c1d2f3e-0000-4000-8000-000000000001");

    private static NotifyProperties props(String secret) {
        return new NotifyProperties("notify.pages", 4, "https://pagerewind.dev", "http://incident", Duration.ofMinutes(2),
                new NotifyProperties.Retry(3, Duration.ofMillis(1), Duration.ofMillis(1)),
                new NotifyProperties.ActionLinks(secret, Duration.ofMinutes(30)),
                new NotifyProperties.Telegram("", "", "http://t", Duration.ofSeconds(1)),
                new NotifyProperties.Email("from@example.com"));
    }

    private static ActionTokens tokens(String secret, Instant now) {
        return new ActionTokens(props(secret), Clock.fixed(now, ZoneOffset.UTC));
    }

    private final String secret = UUID.randomUUID().toString();

    @Test
    void roundTrip() {
        String token = tokens(secret, NOW).create(INCIDENT, ActionTokens.Action.RESOLVE, "alice");
        ActionTokens.Claims claims = tokens(secret, NOW.plusSeconds(60)).verify(token);

        assertThat(claims.incidentId()).isEqualTo(INCIDENT);
        assertThat(claims.action()).isEqualTo(ActionTokens.Action.RESOLVE);
        assertThat(claims.personId()).isEqualTo("alice");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(claims.nonce()).isNotBlank();
        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    @Test
    void everyTokenHasItsOwnNonce() {
        ActionTokens t = tokens(secret, NOW);
        String a = t.create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        String b = t.create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        assertThat(a).isNotEqualTo(b);
        assertThat(t.verify(a).nonce()).isNotEqualTo(t.verify(b).nonce());
    }

    @Test
    void expiresAfterTheTtl() {
        String token = tokens(secret, NOW).create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        assertThat(tokens(secret, NOW.plus(Duration.ofMinutes(30)).minusSeconds(1)).verify(token)).isNotNull();
        assertThatThrownBy(() -> tokens(secret, NOW.plus(Duration.ofMinutes(30))).verify(token))
                .isInstanceOf(ActionTokens.ExpiredToken.class);
    }

    @Test
    void changingThePayloadBreaksTheSignature() {
        String token = tokens(secret, NOW).create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace("ACKNOWLEDGE", "RESOLVE").getBytes(StandardCharsets.UTF_8))
                + token.substring(token.indexOf('.'));

        assertThatThrownBy(() -> tokens(secret, NOW).verify(forged)).isInstanceOf(ActionTokens.InvalidToken.class);
    }

    @Test
    void otherSecretIsRejected() {
        String token = tokens(secret, NOW).create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        assertThatThrownBy(() -> tokens(UUID.randomUUID().toString(), NOW).verify(token))
                .isInstanceOf(ActionTokens.InvalidToken.class);
    }

    @Test
    void malformedTokensAreRejected() {
        ActionTokens t = tokens(secret, NOW);
        for (String bad : new String[] {null, "", "abc", "a.b.c", ".abc", "!!!.###", "YWJj.YWJj"}) {
            assertThatThrownBy(() -> t.verify(bad)).as(String.valueOf(bad)).isInstanceOf(ActionTokens.InvalidToken.class);
        }
    }

    @Test
    void linksAreOffWithoutASecret() {
        ActionTokens off = tokens("", NOW);
        assertThat(off.enabled()).isFalse();
        String token = tokens(secret, NOW).create(INCIDENT, ActionTokens.Action.ACKNOWLEDGE, "alice");
        assertThatThrownBy(() -> off.verify(token)).isInstanceOf(ActionTokens.InvalidToken.class);
    }
}

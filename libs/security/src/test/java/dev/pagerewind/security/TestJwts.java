package dev.pagerewind.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test stand-in for Keycloak: serves a JWK set over HTTP and signs access tokens shaped like the ones
 * Keycloak issues, so tests go through the real token checks of the service.
 */
public final class TestJwts {

    public static final String ISSUER = "https://auth.pagerewind.test/realms/pagerewind";
    public static final String AUDIENCE = "pagerewind-api";

    private static final TestJwts INSTANCE = new TestJwts();

    private final RSAKey key;
    private final String jwkSetUri;

    private TestJwts() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
            jwkSetUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TestJwts get() {
        return INSTANCE;
    }

    /** Points the service's token checks at this stand-in. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> INSTANCE.jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
    }

    /** A valid token for a person with these realm roles. */
    public String person(String username, String... roles) {
        return sign(key, baseClaims(username, roles).build());
    }

    /** A valid client-credentials token, as Keycloak issues to a service account. */
    public String service(String clientId, String... roles) {
        return sign(key, baseClaims(Callers.SERVICE_ACCOUNT_PREFIX + clientId, roles).claim("azp", clientId).build());
    }

    /** A token for a person with these roles, changed by {@code customize} (to test rejections). */
    public String custom(String username, List<String> roles, UnaryOperator<JWTClaimsSet.Builder> customize) {
        return sign(key, customize.apply(baseClaims(username, roles.toArray(String[]::new))).build());
    }

    /** Signed by a key the service does not know, with the same key id. */
    public String signedByStranger(String username, String... roles) {
        try {
            RSAKey stranger = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
            return sign(stranger, baseClaims(username, roles).build());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder baseClaims(String username, String[] roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(List.of(AUDIENCE, "account"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .claim("preferred_username", username)
                .claim("azp", "pagerewind-web")
                .claim("realm_access", Map.of("roles", List.of(roles)));
    }

    private static String sign(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

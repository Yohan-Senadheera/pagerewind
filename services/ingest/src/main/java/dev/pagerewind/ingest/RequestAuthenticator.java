package dev.pagerewind.ingest;

import dev.pagerewind.ingest.IngestProperties.SourceCredentials;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Checks that a request really comes from a configured alert source.
 *
 * <p>A request is accepted if either the bearer token or the body signature is valid. All
 * comparisons are constant-time, so response timing does not leak how much of a secret matched.
 */
@Component
public class RequestAuthenticator {

    static final String SIGNATURE_PREFIX = "sha256=";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final IngestProperties properties;

    public RequestAuthenticator(IngestProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthentic(String source, byte[] body, String authorization, String signature) {
        SourceCredentials credentials = properties.credentialsFor(source);
        boolean tokenOk = credentials.hasToken() && tokenMatches(credentials.token(), authorization);
        boolean signatureOk = credentials.hasHmacSecret() && signatureMatches(credentials.hmacSecret(), body, signature);
        return tokenOk || signatureOk;
    }

    static String sign(String secret, byte[] body) {
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(hmac(secret, body));
    }

    private static boolean tokenMatches(String expected, String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER_PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented);
    }

    private static boolean signatureMatches(String secret, byte[] body, String signature) {
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(signature.substring(SIGNATURE_PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), presented);
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}

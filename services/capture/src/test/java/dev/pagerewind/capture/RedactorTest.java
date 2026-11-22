package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RedactorTest {

    private final Redactor redactor = new Redactor(List.of("card=\\d{12,19}"));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "GET /api Authorization: Bearer abc.def-123 ok | GET /api Authorization: Bearer [REDACTED] ok",
        "authorization=Basic dXNlcjpwdw== | authorization=Basic [REDACTED]",
        "Authorization: sometoken | Authorization: [REDACTED]",
        "login password=hunter2 user=bob | login password=[REDACTED] user=bob",
        "{\"client_secret\": \"s3cr3t\", \"id\": 7} | {\"client_secret\": \"[REDACTED]\", \"id\": 7}",
        "api-key: 12345, region: x | api-key: [REDACTED], region: x",
        "connecting to postgres://app:pa55@db:5432/x | connecting to postgres://app:[REDACTED]@db:5432/x",
        "paid with card=4111111111111111 | paid with [REDACTED]",
    })
    void credentialsAreMasked(String line, String expected) {
        assertThat(redactor.redact(line)).isEqualTo(expected);
    }

    @Test
    void jsonWebTokensAreMaskedAnywhere() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
        assertThat(redactor.redact("token in body: " + jwt + " end")).isEqualTo("token in body: [REDACTED] end");
    }

    @Test
    void ordinaryLinesAreUnchanged() {
        String line = "2026-11-23 INFO Checkout failed: upstream returned 503 after 3 retries (order 9871)";
        assertThat(redactor.redact(line)).isEqualTo(line);
    }

    @Test
    void replacementTextIsTakenLiterally() {
        assertThat(redactor.redact("password=$1abc")).isEqualTo("password=[REDACTED]");
    }
}

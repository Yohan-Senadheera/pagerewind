package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Who may do what: every API needs a valid Keycloak token, and the role decides what it may change. */
class SecurityIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID openIncident() {
        String fingerprint = newFingerprint();
        service.handleAlert(alert(fingerprint));
        return jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
    }

    @Test
    void requestsWithoutAValidTokenGet401() throws Exception {
        UUID id = openIncident();
        String path = "/v1/incidents/" + id;

        assertThat(send("GET", path, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/ui", null).statusCode()).isEqualTo(401);
        assertThat(send("POST", path + "/acknowledge", null).statusCode()).isEqualTo(401);
        assertThat(send("GET", path, "not-a-jwt").statusCode()).isEqualTo(401);

        String expired = TOKENS.custom("alice", List.of("responder"), claims -> claims
                .issueTime(Date.from(Instant.now().minusSeconds(900)))
                .expirationTime(Date.from(Instant.now().minusSeconds(600))));
        String otherIssuer = TOKENS.custom("alice", List.of("responder"), claims -> claims.issuer("https://evil.example/realms/x"));
        String otherAudience = TOKENS.custom("alice", List.of("responder"), claims -> claims.audience("account"));
        String forged = TOKENS.signedByStranger("alice", "responder");

        for (String token : List.of(expired, otherIssuer, otherAudience, forged)) {
            HttpResponse<String> response = send("POST", path + "/acknowledge", token);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).startsWith("Bearer"));
        }
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.TRIGGERED);
    }

    @Test
    void viewersCanReadButNotChangeIncidents() throws Exception {
        UUID id = openIncident();
        String viewer = TOKENS.person("vera", "viewer");

        assertThat(send("GET", "/v1/incidents/" + id, viewer).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/v1/incidents", viewer).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/v1/incidents/" + id + "/acknowledge", viewer).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/incidents/" + id + "/resolve", viewer).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/incidents/" + id + "/notes", viewer, "{\"text\":\"hi\"}").statusCode()).isEqualTo(403);

        HttpResponse<String> page = send("GET", "/ui/incidents/" + id, viewer);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain("<button class=\"primary\"").contains("Ask an admin for the responder role");
        assertThat(sendForm("/ui/incidents/" + id + "/acknowledge", viewer).statusCode()).isEqualTo(403);

        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.TRIGGERED);
        assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'INCIDENT_ACKNOWLEDGED'")).isZero();
    }

    @Test
    void tokensWithoutAPageRewindRoleAreForbidden() throws Exception {
        UUID id = openIncident();
        String nobody = TOKENS.person("nina", "offline_access");

        assertThat(send("GET", "/v1/incidents/" + id, nobody).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/ui", nobody).statusCode()).isEqualTo(403);
    }

    @Test
    void adminsIncludeTheResponderRole() throws Exception {
        UUID id = openIncident();

        HttpResponse<String> ack = send("POST", "/v1/incidents/" + id + "/acknowledge", TOKENS.person("root", "admin"));

        assertThat(ack.statusCode()).isEqualTo(200);
        assertThat(service.timeline(id).getLast().actor()).isEqualTo("root");
    }

    @Test
    void aServiceMayActForThePersonItPagedButAPersonCannotPretend() throws Exception {
        UUID byService = openIncident();
        UUID byPerson = openIncident();
        String notify = TOKENS.service("notify-svc", "responder");
        String mallory = TOKENS.person("mallory", "responder");

        assertThat(send("POST", "/v1/incidents/" + byService + "/acknowledge", notify, "{\"actor\":\"alice\"}").statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/v1/incidents/" + byPerson + "/acknowledge", mallory, "{\"actor\":\"alice\"}").statusCode())
                .isEqualTo(200);

        assertThat(service.timeline(byService).getLast().actor()).isEqualTo("alice");
        assertThat(service.timeline(byPerson).getLast().actor()).isEqualTo("mallory");
    }

    @Test
    void probesMetricsAndApiDocsNeedNoToken() throws Exception {
        assertThat(send("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/prometheus", null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/v3/api-docs", null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/actuator/env", null).statusCode()).isIn(401, 404);
    }

    @Test
    void pagesShowTheSignedInPersonAndCarryTheGatewayCsrfToken() throws Exception {
        UUID id = openIncident();
        HttpRequest request = HttpRequest.newBuilder(uri("/ui/incidents/" + id))
                .header("Authorization", "Bearer " + TOKENS.person("alice", "responder"))
                .header(UiModel.CSRF_HEADER, "csrf-123").GET().build();

        String page = http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        assertThat(page).contains(">alice<").contains("name=\"_csrf\" value=\"csrf-123\"").contains("Sign out");
    }

    private HttpResponse<String> send(String method, String path, String token) throws IOException, InterruptedException {
        return send(method, path, token, null);
    }

    private HttpResponse<String> send(String method, String path, String token, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendForm(String path, String token) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}

package dev.pagerewind.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway against a real Keycloak that imported the realm file from deploy/keycloak, so this also
 * tests the realm: clients, roles, audience and service accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class GatewayIT {

    private static final int PORT = freePort();
    private static final String GATEWAY = "http://localhost:" + PORT;
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String WEB_SECRET = UUID.randomUUID().toString();
    private static final String NOTIFY_SECRET = UUID.randomUUID().toString();
    private static final String AUTOMATION_SECRET = UUID.randomUUID().toString();
    private static final String PASSWORD = "pw-" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.8.2"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("PAGEREWIND_URL", GATEWAY)
            .withEnv("PAGEREWIND_WEB_CLIENT_SECRET", WEB_SECRET)
            .withEnv("NOTIFY_CLIENT_SECRET", NOTIFY_SECRET)
            .withEnv("AUTOMATION_CLIENT_SECRET", AUTOMATION_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../deploy/keycloak/pagerewind-realm.json")),
                    "/opt/keycloak/data/import/pagerewind-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(4)));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static final Downstream SERVICES = new Downstream();

    static {
        KEYCLOAK.start();
        REDIS.start();
        createUser("alice", "responder");
        createUser("vera", "viewer");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("pagerewind.gateway.issuer", GatewayIT::issuer);
        registry.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> WEB_SECRET);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("pagerewind.gateway.rate-limits.alerts-per-second", () -> 1);
        registry.add("pagerewind.gateway.rate-limits.alerts-burst", () -> 3);
        for (String service : List.of("INCIDENT_URL", "SCHEDULE_URL", "INGEST_URL", "NOTIFY_URL", "CAPTURE_URL")) {
            registry.add(service, SERVICES::url);
        }
    }

    @AfterAll
    static void stop() {
        SERVICES.close();
    }

    @BeforeEach
    void reset() {
        SERVICES.reset();
    }

    // ---- signing in

    @Test
    void pagesSendPeopleToKeycloakWithPkce() throws Exception {
        HttpClient browser = browser();

        HttpResponse<String> page = get(browser, GATEWAY + "/ui/incidents/42");
        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(location(page)).endsWith("/oauth2/authorization/keycloak");

        HttpResponse<String> authorize = get(browser, location(page));
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(location(authorize)).startsWith(issuer() + "/protocol/openid-connect/auth?")
                .contains("client_id=pagerewind-web").contains("code_challenge=").contains("code_challenge_method=S256");
        assertThat(SERVICES.requests()).isEmpty();
        assertThat(get(browser(), GATEWAY + "/").statusCode()).isEqualTo(302);
    }

    @Test
    void afterLoginPagesReceiveThePersonsToken() throws Exception {
        HttpClient browser = browser();

        HttpResponse<String> landed = login(browser, GATEWAY + "/ui/incidents/42", "alice");

        assertThat(landed.statusCode()).isEqualTo(200);
        assertThat(landed.body()).contains("downstream /ui/incidents/42");
        Downstream.Request request = SERVICES.last();
        assertThat(request.authorization()).startsWith("Bearer ");
        Map<String, Object> claims = claims(request.authorization().substring("Bearer ".length()));
        assertThat(claims).containsEntry("iss", issuer()).containsEntry("preferred_username", "alice")
                .containsEntry("azp", "pagerewind-web");
        assertThat(audience(claims)).contains("pagerewind-api");
        assertThat(roles(claims)).contains("responder").doesNotContain("admin", "viewer");
        assertThat(request.csrf()).isNotBlank();

        get(browser, GATEWAY + "/ui/schedules");
        assertThat(SERVICES.last().path()).isEqualTo("/ui/schedules");
        assertThat(SERVICES.last().authorization()).isEqualTo(request.authorization());

        get(browser, GATEWAY + "/ui/evidence/7b1f6c1e-0000-4000-8000-000000000001");
        assertThat(SERVICES.last().path()).isEqualTo("/ui/evidence/7b1f6c1e-0000-4000-8000-000000000001");
        assertThat(SERVICES.last().authorization()).as("evidence downloads carry the person's token").isEqualTo(request.authorization());
    }

    @Test
    void viewersGetATokenWithOnlyTheViewerRole() throws Exception {
        login(browser(), GATEWAY + "/ui", "vera");

        assertThat(roles(claims(SERVICES.last().authorization().substring(7)))).contains("viewer").doesNotContain("responder", "admin");
    }

    @Test
    void wrongPasswordsDoNotGetIn() throws Exception {
        HttpClient browser = browser();
        HttpResponse<String> loginPage = followUntilPage(browser, get(browser, GATEWAY + "/ui"));

        HttpResponse<String> refused = post(browser, loginAction(loginPage),
                form("username", "alice", "password", "wrong-password", "credentialId", ""));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(200);
        assertThat(refused.body()).contains("Invalid username or password");
        assertThat(SERVICES.requests()).isEmpty();
    }

    // ---- forms and CSRF

    @Test
    void formsNeedTheCsrfTokenTheGatewayGaveThePage() throws Exception {
        HttpClient browser = browser();
        login(browser, GATEWAY + "/ui/incidents/42", "alice");
        String csrf = SERVICES.last().csrf();
        SERVICES.reset();

        HttpResponse<String> withoutToken = post(browser, GATEWAY + "/ui/incidents/42/acknowledge", form("note", "on it"));
        HttpResponse<String> forgedHeader = send(browser, HttpRequest.newBuilder(URI.create(GATEWAY + "/ui/incidents/42/acknowledge"))
                .header("Content-Type", "application/x-www-form-urlencoded").header(CsrfRelayFilter.HEADER, csrf)
                .POST(HttpRequest.BodyPublishers.ofString(form("note", "on it"))).build());
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        assertThat(forgedHeader.statusCode()).as("the relay header is not a way to send the token").isEqualTo(403);
        assertThat(SERVICES.requests()).isEmpty();

        String body = form("_csrf", csrf, "note", "rolled back v42 & restarted");
        HttpResponse<String> accepted = post(browser, GATEWAY + "/ui/incidents/42/acknowledge", body);

        assertThat(accepted.statusCode()).isEqualTo(200);
        Downstream.Request forwarded = SERVICES.last();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/ui/incidents/42/acknowledge");
        assertThat(forwarded.body()).isEqualTo("note=rolled+back+v42+%26+restarted");
        assertThat(forwarded.contentLength()).isEqualTo(String.valueOf(forwarded.body().length()));
        assertThat(forwarded.authorization()).startsWith("Bearer ");
    }

    @Test
    void browsersCannotSetTheCsrfRelayHeader() throws Exception {
        HttpClient browser = browser();
        login(browser, GATEWAY + "/ui", "alice");

        send(browser, HttpRequest.newBuilder(URI.create(GATEWAY + "/ui")).header(CsrfRelayFilter.HEADER, "chosen-by-attacker").GET().build());
        send(browser, HttpRequest.newBuilder(URI.create(GATEWAY + "/api/v1/incidents")).header(CsrfRelayFilter.HEADER, "x").GET().build());

        List<Downstream.Request> requests = SERVICES.requests();
        assertThat(requests.get(requests.size() - 2).csrf()).isNotBlank().isNotEqualTo("chosen-by-attacker");
        assertThat(requests.getLast().csrf()).isNull();
    }

    // ---- APIs

    @Test
    void apisForwardTheCallersOwnTokenButNeverTheSession() throws Exception {
        HttpClient browser = browser();
        login(browser, GATEWAY + "/ui", "alice");
        SERVICES.reset();

        get(browser, GATEWAY + "/api/v1/incidents?limit=5");
        assertThat(SERVICES.last().path()).isEqualTo("/v1/incidents");
        assertThat(SERVICES.last().authorization()).as("a signed-in browser gets no token on /api").isNull();

        HttpResponse<String> posted = send(browser(), HttpRequest.newBuilder(URI.create(GATEWAY + "/api/v1/teams/ops"))
                .header("Authorization", "Bearer caller-token").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ops\"}")).build());
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(SERVICES.last().path()).isEqualTo("/v1/teams/ops");
        assertThat(SERVICES.last().authorization()).isEqualTo("Bearer caller-token");
        assertThat(SERVICES.last().body()).isEqualTo("{\"name\":\"Ops\"}");
    }

    @Test
    void serviceAccountsGetApiTokensWithTheirRole() throws Exception {
        Map<String, Object> notify = claims(clientCredentials("notify-svc", NOTIFY_SECRET).get("access_token").toString());
        Map<String, Object> automation = claims(clientCredentials("pagerewind-automation", AUTOMATION_SECRET).get("access_token").toString());

        assertThat(notify).containsEntry("preferred_username", "service-account-notify-svc").containsEntry("iss", issuer());
        assertThat(audience(notify)).contains("pagerewind-api");
        assertThat(roles(notify)).contains("responder").doesNotContain("admin");
        assertThat(automation).containsEntry("preferred_username", "service-account-pagerewind-automation");
        assertThat(audience(automation)).contains("pagerewind-api");
        assertThat(roles(automation)).contains("admin");

        assertThat(tokenRequest(form("grant_type", "client_credentials", "client_id", "notify-svc", "client_secret", "wrong"))
                .statusCode()).isEqualTo(401);
        assertThat(tokenRequest(form("grant_type", "password", "client_id", "pagerewind-web", "client_secret", WEB_SECRET,
                "username", "alice", "password", PASSWORD)).statusCode()).as("no password grant for the web client").isIn(400, 401);
        assertThat(tokenRequest(form("grant_type", "client_credentials", "client_id", "pagerewind-web", "client_secret", WEB_SECRET))
                .statusCode()).as("the web client has no service account").isIn(400, 401);
    }

    // ---- public webhooks

    @Test
    void webhooksAreRateLimitedPerClientAddress() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(send(browser(), HttpRequest.newBuilder(URI.create(GATEWAY + "/v1/alerts/alertmanager"))
                    .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113." + i)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode());
        }

        assertThat(statuses).contains(200, 429);
        assertThat(statuses.subList(0, 3)).containsOnly(200);
        assertThat(SERVICES.requests()).hasSize((int) statuses.stream().filter(s -> s == 200).count())
                .allSatisfy(r -> assertThat(r.path()).isEqualTo("/v1/alerts/alertmanager"));
        assertThat(get(browser(), GATEWAY + "/v1/actions/some-token").statusCode()).as("other routes have their own bucket").isEqualTo(200);
    }

    // ---- signing out and other paths

    @Test
    void signingOutEndsTheKeycloakSessionToo() throws Exception {
        HttpClient browser = browser();
        login(browser, GATEWAY + "/ui", "alice");
        String csrf = SERVICES.last().csrf();

        HttpResponse<String> logout = post(browser, GATEWAY + "/logout", form("_csrf", csrf));

        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(location(logout)).startsWith(issuer() + "/protocol/openid-connect/logout?")
                .contains("id_token_hint=").contains("post_logout_redirect_uri=" + GATEWAY + "/&");
        HttpResponse<String> backHome = get(browser, location(logout));
        assertThat(backHome.statusCode()).isEqualTo(302);
        assertThat(location(backHome)).isEqualTo(GATEWAY + "/");

        HttpResponse<String> again = followUntilPage(browser, get(browser, GATEWAY + "/ui"));
        assertThat(again.body()).as("a new login is needed").contains("kc-form-login");
    }

    @Test
    void probesAreOpenAndUnknownPathsAreNot() throws Exception {
        assertThat(probe("/actuator/health/readiness")).isEqualTo(200);
        assertThat(probe("/actuator/prometheus")).isEqualTo(200);
        assertThat(get(browser(), GATEWAY + "/actuator/env").statusCode()).isNotEqualTo(200);
        assertThat(get(browser(), GATEWAY + "/internal").statusCode()).isNotEqualTo(200);
        assertThat(SERVICES.requests()).isEmpty();
    }

    // ---- helpers

    /** Like the kubelet or Prometheus: no cookies and no Accept header. */
    private static int probe(String path) throws Exception {
        return send(HttpClient.newHttpClient(), HttpRequest.newBuilder(URI.create(GATEWAY + path)).GET().build()).statusCode();
    }

    private static String issuer() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/realms/pagerewind";
    }

    /**
     * A cookie-keeping client. Real browsers treat http://localhost as secure and send Secure cookies to it;
     * Java's cookie manager does not, so this one drops the Secure flag (test only, localhost only).
     */
    private static HttpClient browser() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler localhost = new CookieHandler() {
            @Override
            public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) throws IOException {
                return cookies.get(uri, headers);
            }

            @Override
            public void put(URI uri, Map<String, List<String>> headers) throws IOException {
                Map<String, List<String>> insecure = new java.util.HashMap<>();
                headers.forEach((name, values) -> insecure.put(name, name != null && name.equalsIgnoreCase("Set-Cookie")
                        ? values.stream().map(v -> v.replaceAll("(?i);\s*Secure", "")).toList() : values));
                cookies.put(uri, insecure);
            }
        };
        return HttpClient.newBuilder().cookieHandler(localhost).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Signs in through the real Keycloak login form and returns the page the gateway finally shows. */
    private static HttpResponse<String> login(HttpClient browser, String page, String username) throws Exception {
        HttpResponse<String> loginPage = followUntilPage(browser, get(browser, page));
        HttpResponse<String> afterLogin = post(browser, loginAction(loginPage),
                form("username", username, "password", PASSWORD, "credentialId", ""));
        return followUntilPage(browser, afterLogin);
    }

    private static String loginAction(HttpResponse<String> loginPage) {
        Matcher action = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"|<form[^>]*action=\"([^\"]+)\"[^>]*id=\"kc-form-login\"")
                .matcher(loginPage.body());
        assertThat(action.find()).as("Keycloak login form in %s", loginPage.body()).isTrue();
        String url = action.group(1) != null ? action.group(1) : action.group(2);
        return url.replace("&amp;", "&");
    }

    private static HttpResponse<String> followUntilPage(HttpClient browser, HttpResponse<String> response) throws Exception {
        for (int i = 0; i < 10 && response.statusCode() / 100 == 3; i++) {
            response = get(browser, location(response));
        }
        return response;
    }

    private static String location(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElseThrow();
        return URI.create(response.uri().toString()).resolve(location).toString();
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/html,application/xhtml+xml").GET().build());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String form) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String form(String... pairs) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.add(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8) + "=" + URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", fields);
    }

    private static Map<String, Object> claims(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return JSON.readValue(payload, new TypeReference<Map<String, Object>>() {
        });
    }

    private static List<String> audience(Map<String, Object> claims) {
        Object aud = claims.get("aud");
        return aud instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(String.valueOf(aud));
    }

    @SuppressWarnings("unchecked")
    private static List<String> roles(Map<String, Object> claims) {
        Map<String, Object> realmAccess = (Map<String, Object>) claims.getOrDefault("realm_access", Map.of());
        return (List<String>) realmAccess.getOrDefault("roles", List.of());
    }

    private static HttpResponse<String> tokenRequest(String form) throws Exception {
        return post(HttpClient.newHttpClient(), issuer() + "/protocol/openid-connect/token", form);
    }

    private static Map<String, Object> clientCredentials(String clientId, String secret) throws Exception {
        HttpResponse<String> response = tokenRequest(form("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
    }

    /** Creates a person with a password and one realm role through the Keycloak admin API. */
    private static void createUser(String username, String role) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + KEYCLOAK.getMappedPort(8080);
            HttpResponse<String> tokenResponse = post(http, base + "/realms/master/protocol/openid-connect/token",
                    form("grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", ADMIN_PASSWORD));
            String adminToken = JSON.readTree(tokenResponse.body()).get("access_token").asString();
            String user = JSON.writeValueAsString(Map.of("username", username, "enabled", true, "email", username + "@example.com",
                    "emailVerified", true, "firstName", username, "lastName", "Example",
                    "credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))));
            HttpResponse<String> created = send(http, HttpRequest.newBuilder(URI.create(base + "/admin/realms/pagerewind/users"))
                    .header("Authorization", "Bearer " + adminToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(user)).build());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            String userUrl = created.headers().firstValue("Location").orElseThrow();
            String roleJson = send(http, HttpRequest.newBuilder(URI.create(base + "/admin/realms/pagerewind/roles/" + role))
                    .header("Authorization", "Bearer " + adminToken).GET().build()).body();
            HttpResponse<String> mapped = send(http, HttpRequest.newBuilder(URI.create(userUrl + "/role-mappings/realm"))
                    .header("Authorization", "Bearer " + adminToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("[" + roleJson + "]")).build());
            assertThat(mapped.statusCode()).as(mapped.body()).isEqualTo(204);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create user " + username, e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

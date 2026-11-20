package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.Severity;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class IncidentUiIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final String alice = TOKENS.person("alice", "responder");

    private UUID open(String summary) {
        String fingerprint = newFingerprint();
        Instant now = Instant.now();
        service.handleAlert(new AlertReceived(UUID.randomUUID(), now, 1, "test", "checkout", Severity.CRITICAL,
                AlertStatus.FIRING, summary, Map.of(), fingerprint, false, now));
        return jdbc.sql("SELECT id FROM incident WHERE fingerprint = ?").param(fingerprint).query(UUID.class).single();
    }

    @Test
    void listShowsOpenIncidentsAndLinksToThem() throws Exception {
        UUID open = open("Checkout 5xx above 5%");
        UUID closed = open("Old problem");
        service.resolve(closed, "alice", null);

        String page = get("/ui").body();
        assertThat(page).contains("Open incidents").contains("Checkout 5xx above 5%").contains("/ui/incidents/" + open)
                .doesNotContain("Old problem");
        assertThat(get("/ui?show=all").body()).contains("Old problem");
    }

    @Test
    void alertTextIsEscapedNotRendered() throws Exception {
        UUID id = open("<script>alert('x')</script> & <b>bold</b>");

        String list = get("/ui").body();
        String detail = get("/ui/incidents/" + id).body();

        for (String page : new String[] {list, detail}) {
            assertThat(page).doesNotContain("<script>alert").doesNotContain("<b>bold</b>")
                    .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &lt;b&gt;bold&lt;/b&gt;");
        }
    }

    @Test
    void acknowledgeAndResolveFromTheDetailPage() throws Exception {
        UUID id = open("Checkout 5xx above 5%");
        String detail = get("/ui/incidents/" + id).body();
        assertThat(detail).contains("Acknowledge").contains("Resolve").contains("TRIGGERED");

        HttpResponse<String> ack = post("/ui/incidents/" + id + "/acknowledge");
        assertThat(ack.statusCode()).isEqualTo(302);
        assertThat(ack.headers().firstValue("Location")).as("relative, so it works through the gateway")
                .hasValue("/ui/incidents/" + id);
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        String after = get("/ui/incidents/" + id).body();
        assertThat(after).contains("Incident acknowledged.").contains("Acknowledged by alice");

        HttpResponse<String> again = post("/ui/incidents/" + id + "/acknowledge");
        assertThat(again.statusCode()).isEqualTo(302);
        assertThat(get("/ui/incidents/" + id).body()).contains("cannot become ACKNOWLEDGED");

        post("/ui/incidents/" + id + "/resolve");
        assertThat(service.get(id).status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(get("/ui/incidents/" + id).body()).doesNotContain("<button");
    }

    @Test
    void unknownIncidentIs404() throws Exception {
        assertThat(get("/ui/incidents/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + alice).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + alice)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}

package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pagerewind.capture.LogBuffer.ContainerKey;
import dev.pagerewind.capture.LogBuffer.LogLine;
import dev.pagerewind.security.TestJwts;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Day 7 checkpoint against a real Kubernetes (k3s): a pod's last lines survive its deletion, and an
 * image change is ranked as the likely cause.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CaptureIT.Containers.class)
class CaptureIT {

    private static final Duration WAIT = Duration.ofMinutes(3);

    @SuppressWarnings("resource")
    static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.37.2-k3s1"));

    static {
        K3S.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        }

        /** Used instead of the application's own client, which would read the local kubeconfig. */
        @Bean(destroyMethod = "close")
        @Primary
        KubernetesClient k3sClient() {
            return new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(K3S.getKubeConfigYaml())).build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwts.register(registry);
    }

    @Autowired
    private KubernetesClient k8s;
    @Autowired
    private LogBuffer buffer;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private Environment environment;

    private void namespace(String name, boolean recorded) {
        var builder = new NamespaceBuilder().withNewMetadata().withName(name);
        if (recorded) {
            builder = builder.addToLabels("pagerewind.dev/record", "true");
        }
        k8s.namespaces().resource(builder.endMetadata().build()).serverSideApply();
    }

    private List<String> lines(String namespace, String pod) {
        return buffer.snapshot(k -> k.namespace().equals(namespace) && k.pod().equals(pod)).values().stream()
                .flatMap(List::stream).map(LogLine::text).toList();
    }

    @Test
    void aDeletedPodsLastLinesAreStillThereAndRedacted() {
        namespace("shop", true);
        namespace("other", false);
        String script = "echo starting; echo 'boom: payment failed, password=hunter2'; sleep 3600";
        for (String ns : List.of("shop", "other")) {
            k8s.pods().inNamespace(ns).resource(new PodBuilder()
                    .withNewMetadata().withName("worker").endMetadata()
                    .withNewSpec().withTerminationGracePeriodSeconds(0L)
                    .addNewContainer().withName("app").withImage("busybox:1.37").withCommand("sh", "-c", script).endContainer()
                    .endSpec().build()).create();
        }

        await().atMost(WAIT).untilAsserted(() -> assertThat(lines("shop", "worker")).anyMatch(l -> l.contains("boom")));
        k8s.pods().inNamespace("shop").withName("worker").withGracePeriod(0).delete();
        await().atMost(WAIT).until(() -> k8s.pods().inNamespace("shop").withName("worker").get() == null);

        assertThat(lines("shop", "worker"))
                .contains("starting", "boom: payment failed, password=[REDACTED]")
                .noneMatch(l -> l.contains("hunter2"));
        assertThat(buffer.snapshot(k -> k.namespace().equals("other"))).as("namespaces without the label are not recorded").isEmpty();
        assertThat(buffer.snapshot(k -> true)).containsKey(new ContainerKey("shop", "worker", "app"));
    }

    @Test
    void anImageChangeIsRecordedAndRankedAsTheLikelyCause() throws Exception {
        namespace("checkout", true);
        k8s.apps().deployments().inNamespace("checkout").resource(new DeploymentBuilder()
                .withNewMetadata().withName("checkout").endMetadata()
                .withNewSpec().withReplicas(1)
                .withNewSelector().addToMatchLabels("app", "checkout").endSelector()
                .withNewTemplate().withNewMetadata().addToLabels("app", "checkout").endMetadata()
                .withNewSpec().addNewContainer().withName("app").withImage("busybox:1.36").withCommand("sleep", "3600")
                .endContainer().endSpec().endTemplate().endSpec().build()).create();
        k8s.configMaps().inNamespace("checkout").resource(new ConfigMapBuilder()
                .withNewMetadata().withName("checkout-config").endMetadata().withData(Map.of("timeout", "5s")).build()).create();

        await().atMost(WAIT).untilAsserted(() -> assertThat(count("CREATED", "checkout")).isEqualTo(1));
        k8s.configMaps().inNamespace("checkout").withName("checkout-config")
                .edit(cm -> new ConfigMapBuilder(cm).withData(Map.of("timeout", "1s")).build());
        k8s.apps().deployments().inNamespace("checkout").withName("checkout")
                .edit(d -> new DeploymentBuilder(d).editSpec().editTemplate().editSpec().editFirstContainer()
                        .withImage("busybox:1.37").endContainer().endSpec().endTemplate().endSpec().build());

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(count("IMAGE", "checkout")).isEqualTo(1);
            assertThat(count("CONFIG", "checkout-config")).isEqualTo(1);
        });

        String token = TestJwts.get().person("alice", "responder");
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + environment.getProperty("local.server.port")
                        + "/v1/changes?namespace=checkout&workload=checkout&at=" + Instant.now().plusSeconds(1)))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode view = json.readTree(response.body());
        assertThat(view.get("likelyCause").get("reason").asString())
                .startsWith("Deployment checkout changed image of container app to busybox:1.37")
                .endsWith("(same workload)");
        assertThat(view.get("likelyCause").get("change").get("details").get("from").asString()).isEqualTo("busybox:1.36");
    }

    @Test
    void theRecorderStatusAndChangesNeedTheRightRole() throws Exception {
        String base = "http://localhost:" + environment.getProperty("local.server.port");
        HttpClient http = HttpClient.newHttpClient();
        int anonymous = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/changes")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        int viewer = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/changes"))
                .header("Authorization", "Bearer " + TestJwts.get().person("vera", "viewer")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        int status = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/recorder"))
                .header("Authorization", "Bearer " + TestJwts.get().person("vera", "viewer")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();

        assertThat(anonymous).isEqualTo(401);
        assertThat(viewer).isEqualTo(403);
        assertThat(status).isEqualTo(200);
    }

    private long count(String kind, String name) {
        return jdbc.sql("SELECT count(*) FROM change_event WHERE kind = ? AND name = ?").params(kind, name)
                .query(Long.class).single();
    }
}

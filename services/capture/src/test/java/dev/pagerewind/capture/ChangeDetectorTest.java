package dev.pagerewind.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.capture.ChangeEvent.Kind;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeDetectorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private static Deployment deployment(String image, int replicas, String envValue) {
        return new DeploymentBuilder()
                .withNewMetadata().withNamespace("shop").withName("checkout").withGeneration(4L).endMetadata()
                .withNewSpec().withReplicas(replicas)
                .withNewTemplate().withNewSpec()
                .addNewContainer().withName("app").withImage(image).addNewEnv().withName("MODE").withValue(envValue).endEnv().endContainer()
                .addNewContainer().withName("sidecar").withImage("proxy:1").endContainer()
                .endSpec().endTemplate().endSpec().build();
    }

    @Test
    void anImageChangeIsReportedWithOldAndNewImage() {
        List<ChangeEvent> changes = ChangeDetector.deployment(deployment("shop/checkout:1.4", 2, "a"),
                deployment("shop/checkout:1.5", 2, "a"), NOW);

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.IMAGE);
            assertThat(c.namespace()).isEqualTo("shop");
            assertThat(c.workload()).isEqualTo("checkout");
            assertThat(c.summary()).isEqualTo("Deployment checkout changed image of container app to shop/checkout:1.5");
            assertThat(c.details()).containsEntry("from", "shop/checkout:1.4").containsEntry("to", "shop/checkout:1.5");
        });
    }

    @Test
    void otherTemplateChangesAreARolloutAndScalingIsSeparate() {
        assertThat(ChangeDetector.deployment(deployment("x:1", 2, "a"), deployment("x:1", 2, "b"), NOW))
                .extracting(ChangeEvent::kind).containsExactly(Kind.ROLLOUT);
        assertThat(ChangeDetector.deployment(deployment("x:1", 2, "a"), deployment("x:1", 5, "a"), NOW))
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo(Kind.SCALE);
                    assertThat(c.summary()).isEqualTo("Deployment checkout scaled from 2 to 5 replicas");
                });
        assertThat(ChangeDetector.deployment(deployment("x:1", 2, "a"), deployment("x:1", 2, "a"), NOW)).isEmpty();
    }

    private static ConfigMap configMap(Map<String, String> data) {
        return new ConfigMapBuilder().withNewMetadata().withNamespace("shop").withName("checkout-config").endMetadata()
                .withData(data).build();
    }

    @Test
    void configMapChangesNameTheKeysButNeverTheValues() {
        ChangeEvent change = ChangeDetector.configMap(configMap(Map.of("timeout", "5s", "db_password", "old")),
                configMap(Map.of("timeout", "1s", "db_password", "new", "feature", "on")), NOW).orElseThrow();

        assertThat(change.kind()).isEqualTo(Kind.CONFIG);
        assertThat(change.summary()).startsWith("ConfigMap checkout-config changed (")
                .contains("timeout").contains("db_password").contains("feature")
                .doesNotContain("1s").doesNotContain("new").doesNotContain("old");
        assertThat(ChangeDetector.configMap(configMap(Map.of("a", "1")), configMap(Map.of("a", "1")), NOW)).isEmpty();
    }

    private static Node node(String ready) {
        return new NodeBuilder().withNewMetadata().withName("agent").endMetadata()
                .withNewStatus().addNewCondition().withType("Ready").withStatus(ready).endCondition().endStatus().build();
    }

    @Test
    void nodeReadinessChangesAreClusterWide() {
        ChangeEvent change = ChangeDetector.node(node("True"), node("False"), NOW).orElseThrow();

        assertThat(change.namespace()).isNull();
        assertThat(change.summary()).isEqualTo("Node agent became NotReady");
        assertThat(ChangeDetector.node(node("True"), node("True"), NOW)).isEmpty();
    }

    private static HorizontalPodAutoscaler hpa(Integer desired) {
        return new HorizontalPodAutoscalerBuilder().withNewMetadata().withNamespace("shop").withName("checkout-hpa").endMetadata()
                .withNewSpec().withNewScaleTargetRef().withKind("Deployment").withName("checkout").endScaleTargetRef().endSpec()
                .withNewStatus().withDesiredReplicas(desired).endStatus().build();
    }

    @Test
    void autoscalerDecisionsAreAttributedToTheirTarget() {
        ChangeEvent change = ChangeDetector.autoscaler(hpa(2), hpa(6), NOW).orElseThrow();

        assertThat(change.workload()).isEqualTo("checkout");
        assertThat(change.summary()).isEqualTo("Autoscaler set checkout to 6 replicas (was 2)");
        assertThat(ChangeDetector.autoscaler(hpa(2), hpa(2), NOW)).isEmpty();
    }

    private static GenericKubernetesResource argoApp(String finishedAt, String revision) {
        return new GenericKubernetesResourceBuilder().withApiVersion("argoproj.io/v1alpha1").withKind("Application")
                .withNewMetadata().withNamespace("argocd").withName("pagerewind").endMetadata()
                .addToAdditionalProperties("spec", Map.of("destination", Map.of("namespace", "pagerewind")))
                .addToAdditionalProperties("status", Map.of(
                        "sync", Map.of("revision", revision),
                        "operationState", Map.of("phase", "Succeeded", "finishedAt", finishedAt)))
                .build();
    }

    @Test
    void argoCdSyncsAreRecordedOncePerFinishedOperation() {
        GenericKubernetesResource before = argoApp("2026-11-23T09:00:00Z", "aaaaaaa111");
        GenericKubernetesResource after = argoApp("2026-11-23T09:58:00Z", "bbbbbbb222");

        ChangeEvent change = ChangeDetector.argoSync(before, after, NOW).orElseThrow();

        assertThat(change.kind()).isEqualTo(Kind.SYNC);
        assertThat(change.namespace()).isEqualTo("pagerewind");
        assertThat(change.summary()).isEqualTo("Argo CD synced pagerewind to bbbbbbb (Succeeded)");
        assertThat(ChangeDetector.argoSync(after, after, NOW)).isEmpty();
    }
}

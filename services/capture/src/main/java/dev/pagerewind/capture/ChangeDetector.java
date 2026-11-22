package dev.pagerewind.capture;

import dev.pagerewind.capture.ChangeEvent.Kind;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns "this object was X and is now Y" into change events. Pure functions: no cluster access, so each
 * rule is unit-tested with fixed objects.
 */
final class ChangeDetector {

    private ChangeDetector() {
    }

    static List<ChangeEvent> deployment(Deployment before, Deployment after, Instant now) {
        String ns = after.getMetadata().getNamespace();
        String name = after.getMetadata().getName();
        List<ChangeEvent> changes = new ArrayList<>();

        Map<String, String> oldImages = images(before);
        Map<String, String> newImages = images(after);
        for (Map.Entry<String, String> image : newImages.entrySet()) {
            String old = oldImages.get(image.getKey());
            if (old != null && !old.equals(image.getValue())) {
                changes.add(ChangeEvent.of(now, Kind.IMAGE, ns, name, name,
                        "Deployment %s changed image of container %s to %s".formatted(name, image.getKey(), image.getValue()),
                        Map.of("container", image.getKey(), "from", old, "to", image.getValue())));
            }
        }
        boolean templateChanged = !Objects.equals(before.getSpec().getTemplate(), after.getSpec().getTemplate());
        if (templateChanged && changes.isEmpty()) {
            changes.add(ChangeEvent.of(now, Kind.ROLLOUT, ns, name, name,
                    "Deployment %s rolled out a new pod template (generation %s)".formatted(name, after.getMetadata().getGeneration()),
                    Map.of("generation", String.valueOf(after.getMetadata().getGeneration()))));
        }
        Integer oldReplicas = before.getSpec().getReplicas();
        Integer newReplicas = after.getSpec().getReplicas();
        if (!Objects.equals(oldReplicas, newReplicas)) {
            changes.add(ChangeEvent.of(now, Kind.SCALE, ns, name, name,
                    "Deployment %s scaled from %s to %s replicas".formatted(name, oldReplicas, newReplicas),
                    Map.of("from", String.valueOf(oldReplicas), "to", String.valueOf(newReplicas))));
        }
        return changes;
    }

    static Optional<ChangeEvent> deploymentCreated(Deployment created, Instant now) {
        String ns = created.getMetadata().getNamespace();
        String name = created.getMetadata().getName();
        return Optional.of(ChangeEvent.of(now, Kind.CREATED, ns, name, name,
                "Deployment %s was created with %s".formatted(name, String.join(", ", images(created).values())),
                images(created)));
    }

    /** Only that the data changed; values are never recorded (config maps can hold sensitive settings). */
    static Optional<ChangeEvent> configMap(ConfigMap before, ConfigMap after, Instant now) {
        if (Objects.equals(before.getData(), after.getData()) && Objects.equals(before.getBinaryData(), after.getBinaryData())) {
            return Optional.empty();
        }
        String name = after.getMetadata().getName();
        List<String> keys = new ArrayList<>();
        Map<String, String> oldData = before.getData() == null ? Map.of() : before.getData();
        Map<String, String> newData = after.getData() == null ? Map.of() : after.getData();
        for (String key : newData.keySet()) {
            if (!Objects.equals(oldData.get(key), newData.get(key))) {
                keys.add(key);
            }
        }
        for (String key : oldData.keySet()) {
            if (!newData.containsKey(key)) {
                keys.add(key);
            }
        }
        String changedKeys = keys.isEmpty() ? "binary data" : String.join(", ", keys);
        return Optional.of(ChangeEvent.of(now, Kind.CONFIG, after.getMetadata().getNamespace(), name, null,
                "ConfigMap %s changed (%s)".formatted(name, changedKeys), Map.of("keys", changedKeys)));
    }

    static Optional<ChangeEvent> node(Node before, Node after, Instant now) {
        String oldReady = ready(before);
        String newReady = ready(after);
        if (Objects.equals(oldReady, newReady)) {
            return Optional.empty();
        }
        String name = after.getMetadata().getName();
        String state = "True".equals(newReady) ? "Ready" : "NotReady";
        return Optional.of(ChangeEvent.of(now, Kind.NODE, null, name, null, "Node %s became %s".formatted(name, state),
                Map.of("ready", String.valueOf(newReady))));
    }

    static Optional<ChangeEvent> autoscaler(HorizontalPodAutoscaler before, HorizontalPodAutoscaler after, Instant now) {
        Integer oldDesired = before.getStatus() == null ? null : before.getStatus().getDesiredReplicas();
        Integer newDesired = after.getStatus() == null ? null : after.getStatus().getDesiredReplicas();
        if (newDesired == null || Objects.equals(oldDesired, newDesired)) {
            return Optional.empty();
        }
        String target = after.getSpec().getScaleTargetRef().getName();
        return Optional.of(ChangeEvent.of(now, Kind.SCALE, after.getMetadata().getNamespace(), after.getMetadata().getName(),
                target, "Autoscaler set %s to %s replicas (was %s)".formatted(target, newDesired, oldDesired),
                Map.of("from", String.valueOf(oldDesired), "to", String.valueOf(newDesired))));
    }

    /** An Argo CD Application finished a sync to a new revision. */
    @SuppressWarnings("unchecked")
    static Optional<ChangeEvent> argoSync(GenericKubernetesResource before, GenericKubernetesResource after, Instant now) {
        Map<String, Object> oldOp = (Map<String, Object>) path(before.getAdditionalProperties(), "status", "operationState");
        Map<String, Object> newOp = (Map<String, Object>) path(after.getAdditionalProperties(), "status", "operationState");
        if (newOp == null || newOp.get("finishedAt") == null
                || (oldOp != null && Objects.equals(oldOp.get("finishedAt"), newOp.get("finishedAt")))) {
            return Optional.empty();
        }
        String app = after.getMetadata().getName();
        Object revision = path(after.getAdditionalProperties(), "status", "sync", "revision");
        String shortRevision = revision == null ? "?" : revision.toString().substring(0, Math.min(7, revision.toString().length()));
        Object namespace = path(after.getAdditionalProperties(), "spec", "destination", "namespace");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("revision", String.valueOf(revision));
        details.put("phase", String.valueOf(newOp.get("phase")));
        return Optional.of(ChangeEvent.of(now, Kind.SYNC, namespace == null ? null : namespace.toString(), app, null,
                "Argo CD synced %s to %s (%s)".formatted(app, shortRevision, newOp.get("phase")), details));
    }

    private static Map<String, String> images(Deployment deployment) {
        Map<String, String> images = new LinkedHashMap<>();
        if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return images;
        }
        for (Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            images.put(container.getName(), container.getImage());
        }
        return images;
    }

    private static String ready(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return null;
        }
        return node.getStatus().getConditions().stream().filter(c -> "Ready".equals(c.getType()))
                .map(NodeCondition::getStatus).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }
}

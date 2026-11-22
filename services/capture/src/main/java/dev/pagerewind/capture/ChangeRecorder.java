package dev.pagerewind.capture;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Watches the cluster and writes a {@link ChangeEvent} for every rollout, image change, scaling decision,
 * node condition change and Argo CD sync, and for config map changes in recorded namespaces.
 * Read-only: informers only list and watch.
 */
@Component
class ChangeRecorder implements SmartLifecycle, RecordedNamespaces.Listener {

    static final String ARGO_API_VERSION = "argoproj.io/v1alpha1";

    private static final Logger log = LoggerFactory.getLogger(ChangeRecorder.class);

    private final KubernetesClient client;
    private final ChangeRepository repository;
    private final CaptureProperties properties;
    private final Clock clock;
    private final List<SharedIndexInformer<?>> clusterInformers = new CopyOnWriteArrayList<>();
    private final Map<String, SharedIndexInformer<ConfigMap>> configMapInformers = new ConcurrentHashMap<>();
    private volatile Instant startedAt;
    private volatile boolean running;

    ChangeRecorder(KubernetesClient client, ChangeRepository repository, CaptureProperties properties, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void start() {
        running = true;
        if (!properties.recordChanges()) {
            return;
        }
        startedAt = clock.instant();
        clusterInformers.add(client.apps().deployments().inAnyNamespace().inform(handler(
                (before, after) -> ChangeDetector.deployment(before, after, clock.instant()),
                created -> ChangeDetector.deploymentCreated(created, clock.instant()))));
        clusterInformers.add(client.nodes().inform(handler(
                (before, after) -> list(ChangeDetector.node(before, after, clock.instant())), created -> Optional.empty())));
        clusterInformers.add(client.autoscaling().v2().horizontalPodAutoscalers().inAnyNamespace().inform(handler(
                (before, after) -> list(ChangeDetector.autoscaler(before, after, clock.instant())), created -> Optional.empty())));
        if (client.supports(ARGO_API_VERSION, "Application")) {
            clusterInformers.add(client.genericKubernetesResources(ARGO_API_VERSION, "Application").inAnyNamespace()
                    .inform(handler((GenericKubernetesResource before, GenericKubernetesResource after) ->
                            list(ChangeDetector.argoSync(before, after, clock.instant())), created -> Optional.empty())));
        }
        log.info("Recording changes (deployments, nodes, autoscalers{})",
                client.supports(ARGO_API_VERSION, "Application") ? ", Argo CD applications" : "");
    }

    @Override
    public void onRecorded(String namespace) {
        if (!properties.recordChanges()) {
            return;
        }
        configMapInformers.computeIfAbsent(namespace, ns -> client.configMaps().inNamespace(ns).inform(handler(
                (before, after) -> list(ChangeDetector.configMap(before, after, clock.instant())), created -> Optional.empty())));
    }

    @Override
    public void onUnrecorded(String namespace) {
        SharedIndexInformer<ConfigMap> informer = configMapInformers.remove(namespace);
        if (informer != null) {
            informer.close();
        }
    }

    private <T extends HasMetadata> ResourceEventHandler<T> handler(BiFunction<T, T, Collection<ChangeEvent>> onUpdate,
            java.util.function.Function<T, Optional<ChangeEvent>> onCreate) {
        return new ResourceEventHandler<>() {
            @Override
            public void onAdd(T object) {
                // The informer's first list reports everything that already exists; only newer objects are news.
                if (createdAfterStart(object)) {
                    onCreate.apply(object).ifPresent(ChangeRecorder.this::record);
                }
            }

            @Override
            public void onUpdate(T before, T after) {
                if (before.getMetadata().getResourceVersion() != null
                        && before.getMetadata().getResourceVersion().equals(after.getMetadata().getResourceVersion())) {
                    return; // periodic resync, nothing changed
                }
                onUpdate.apply(before, after).forEach(ChangeRecorder.this::record);
            }

            @Override
            public void onDelete(T object, boolean unknownFinalState) {
                // Deletions are visible as missing pods and failed probes; not recorded as changes for now.
            }
        };
    }

    private boolean createdAfterStart(HasMetadata object) {
        String created = object.getMetadata().getCreationTimestamp();
        try {
            return created != null && startedAt != null && Instant.parse(created).isAfter(startedAt);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void record(ChangeEvent change) {
        try {
            repository.insert(change);
            log.info("Change: {}", change.summary());
        } catch (RuntimeException e) {
            log.warn("Could not store change '{}': {}", change.summary(), e.toString());
        }
    }

    private static List<ChangeEvent> list(Optional<ChangeEvent> change) {
        return change.map(List::of).orElse(List.of());
    }

    @Override
    public void stop() {
        running = false;
        clusterInformers.forEach(SharedIndexInformer::close);
        clusterInformers.clear();
        configMapInformers.values().forEach(SharedIndexInformer::close);
        configMapInformers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after the database is ready and before the namespace watcher calls {@link #onRecorded}. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 100;
    }
}

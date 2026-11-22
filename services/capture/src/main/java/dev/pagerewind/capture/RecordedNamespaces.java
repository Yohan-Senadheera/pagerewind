package dev.pagerewind.capture;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Namespaces opt in to recording with the label {@code pagerewind.dev/record=true}. Nothing in other
 * namespaces is followed or read beyond cluster-wide change metadata.
 */
@Component
class RecordedNamespaces implements SmartLifecycle {

    /** Reacts when a namespace starts or stops being recorded. */
    interface Listener {
        void onRecorded(String namespace);

        void onUnrecorded(String namespace);
    }

    private static final Logger log = LoggerFactory.getLogger(RecordedNamespaces.class);

    private final KubernetesClient client;
    private final CaptureProperties properties;
    private final List<Listener> listeners;
    private final Set<String> recorded = ConcurrentHashMap.newKeySet();
    private volatile SharedIndexInformer<Namespace> informer;

    RecordedNamespaces(KubernetesClient client, CaptureProperties properties, List<Listener> listeners) {
        this.client = client;
        this.properties = properties;
        this.listeners = listeners;
    }

    Set<String> current() {
        return Set.copyOf(recorded);
    }

    @Override
    public void start() {
        informer = client.namespaces().withLabel(properties.recordLabel(), "true").inform(new ResourceEventHandler<>() {
            @Override
            public void onAdd(Namespace namespace) {
                added(namespace.getMetadata().getName());
            }

            @Override
            public void onUpdate(Namespace before, Namespace after) {
                added(after.getMetadata().getName());
            }

            @Override
            public void onDelete(Namespace namespace, boolean unknownFinalState) {
                removed(namespace.getMetadata().getName());
            }
        });
    }

    private void added(String namespace) {
        if (recorded.add(namespace)) {
            log.info("Recording namespace {}", namespace);
            listeners.forEach(l -> l.onRecorded(namespace));
        }
    }

    private void removed(String namespace) {
        if (recorded.remove(namespace)) {
            log.info("Stopped recording namespace {}", namespace);
            listeners.forEach(l -> l.onUnrecorded(namespace));
        }
    }

    @Override
    public void stop() {
        if (informer != null) {
            informer.close();
        }
        Set.copyOf(recorded).forEach(this::removed);
    }

    @Override
    public boolean isRunning() {
        return informer != null && informer.isRunning();
    }
}

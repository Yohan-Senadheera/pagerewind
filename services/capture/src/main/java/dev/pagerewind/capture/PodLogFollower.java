package dev.pagerewind.capture;

import dev.pagerewind.capture.LogBuffer.ContainerKey;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Follows the logs of every running container in recorded namespaces into the {@link LogBuffer}. A
 * restarted container has a new container id and is followed again; a deleted pod is marked so its last
 * lines are kept for the retention period.
 */
@Component
class PodLogFollower implements RecordedNamespaces.Listener {

    private static final Logger log = LoggerFactory.getLogger(PodLogFollower.class);

    /** One followed container run: namespace, pod, container and the runtime's container id. */
    private record Run(ContainerKey key, String containerId) {
    }

    private final KubernetesClient client;
    private final LogBuffer buffer;
    private final CaptureProperties properties;
    private final Clock clock;
    private final Map<String, SharedIndexInformer<Pod>> informers = new ConcurrentHashMap<>();
    private final Map<Run, LogWatch> watches = new ConcurrentHashMap<>();

    PodLogFollower(KubernetesClient client, LogBuffer buffer, CaptureProperties properties, Clock clock) {
        this.client = client;
        this.buffer = buffer;
        this.properties = properties;
        this.clock = clock;
    }

    int followedContainers() {
        return watches.size();
    }

    @Override
    public void onRecorded(String namespace) {
        if (!properties.followLogs()) {
            return;
        }
        informers.computeIfAbsent(namespace, ns -> client.pods().inNamespace(ns).inform(new ResourceEventHandler<>() {
            @Override
            public void onAdd(Pod pod) {
                follow(pod);
            }

            @Override
            public void onUpdate(Pod before, Pod after) {
                follow(after);
            }

            @Override
            public void onDelete(Pod pod, boolean unknownFinalState) {
                gone(pod.getMetadata().getNamespace(), pod.getMetadata().getName());
            }
        }));
    }

    @Override
    public void onUnrecorded(String namespace) {
        SharedIndexInformer<Pod> informer = informers.remove(namespace);
        if (informer != null) {
            informer.close();
        }
        for (Run run : List.copyOf(watches.keySet())) {
            if (run.key().namespace().equals(namespace)) {
                close(run);
                buffer.markDeleted(namespace, run.key().pod(), clock.instant());
            }
        }
    }

    private void follow(Pod pod) {
        String namespace = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();
        List<ContainerStatus> statuses = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                ? List.of() : pod.getStatus().getContainerStatuses();
        for (ContainerStatus status : statuses) {
            if (status.getState() == null || status.getState().getRunning() == null || status.getContainerID() == null) {
                continue;
            }
            ContainerKey key = new ContainerKey(namespace, name, status.getName());
            Run run = new Run(key, status.getContainerID());
            if (watches.containsKey(run)) {
                continue;
            }
            // A new run of the same container: the old stream has ended, close it.
            List.copyOf(watches.keySet()).stream().filter(r -> r.key().equals(key)).forEach(this::close);
            Instant now = clock.instant();
            Instant started = parse(status.getState().getRunning().getStartedAt(), now);
            Instant since = started.isAfter(now.minus(properties.window())) ? started : now.minus(properties.window());
            try {
                LogWatch watch = client.pods().inNamespace(namespace).withName(name).inContainer(status.getName())
                        .usingTimestamps()
                        .sinceTime(since.truncatedTo(ChronoUnit.SECONDS).toString())
                        .watchLog(LogLines.splitting(line -> LogLines.parse(line, clock::instant,
                                (time, text) -> buffer.append(key, time, text, clock.instant()))));
                watches.put(run, watch);
                log.debug("Following {}/{} container {}", namespace, name, status.getName());
            } catch (RuntimeException e) {
                log.warn("Cannot follow {}/{} container {}: {}", namespace, name, status.getName(), e.toString());
            }
        }
    }

    private void gone(String namespace, String pod) {
        for (Run run : List.copyOf(watches.keySet())) {
            if (run.key().namespace().equals(namespace) && run.key().pod().equals(pod)) {
                close(run);
            }
        }
        buffer.markDeleted(namespace, pod, clock.instant());
    }

    private void close(Run run) {
        LogWatch watch = watches.remove(run);
        if (watch != null) {
            try {
                watch.close();
            } catch (RuntimeException e) {
                log.debug("Closing log stream of {} failed: {}", run.key(), e.toString());
            }
        }
    }

    private static Instant parse(String time, Instant fallback) {
        try {
            return time == null ? fallback : Instant.parse(time);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

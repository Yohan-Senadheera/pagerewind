package dev.pagerewind.capture;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that changed in the cluster: a rollout, an image, a config map, a scale, a node condition,
 * an Argo CD sync.
 *
 * @param namespace null for cluster-wide changes (nodes)
 * @param workload  the Deployment (or other workload) it belongs to, when known
 * @param details   old and new values that are safe to show (images, replica counts, revisions; never
 *                  config map data)
 */
record ChangeEvent(UUID id, Instant occurredAt, Kind kind, String namespace, String name, String workload,
        String summary, Map<String, String> details) {

    enum Kind {
        IMAGE("image change", 1.0),
        ROLLOUT("rollout", 0.9),
        CONFIG("config change", 0.85),
        SYNC("Argo CD sync", 0.85),
        NODE("node condition", 0.8),
        SCALE("scaling", 0.6),
        CREATED("new workload", 0.7);

        private final String label;
        private final double weight;

        Kind(String label, double weight) {
            this.label = label;
            this.weight = weight;
        }

        String label() {
            return label;
        }

        /** How likely this kind of change is to break something, relative to an image change. */
        double weight() {
            return weight;
        }
    }

    ChangeEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    static ChangeEvent of(Instant occurredAt, Kind kind, String namespace, String name, String workload, String summary,
            Map<String, String> details) {
        return new ChangeEvent(UUID.randomUUID(), occurredAt, kind, namespace, name, workload, summary, details);
    }
}

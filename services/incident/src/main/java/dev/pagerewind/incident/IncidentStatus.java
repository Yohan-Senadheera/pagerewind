package dev.pagerewind.incident;

/**
 * Incident life cycle.
 *
 * <pre>
 * TRIGGERED ──► ACKNOWLEDGED ──► RESOLVED
 *     └────────────────────────────▲
 * </pre>
 */
public enum IncidentStatus {
    TRIGGERED,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean canMoveTo(IncidentStatus target) {
        return switch (this) {
            case TRIGGERED -> target == ACKNOWLEDGED || target == RESOLVED;
            case ACKNOWLEDGED -> target == RESOLVED;
            case RESOLVED -> false;
        };
    }

    public boolean isOpen() {
        return this != RESOLVED;
    }
}

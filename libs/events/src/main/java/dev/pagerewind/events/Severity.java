package dev.pagerewind.events;

import java.util.Locale;

public enum Severity {
    CRITICAL,
    WARNING,
    INFO;

    /**
     * Maps the many spellings used by alerting tools to one of three levels. Unknown or missing
     * values become {@link #WARNING}, so an alert is never dropped because of its label.
     */
    public static Severity parse(String value) {
        if (value == null || value.isBlank()) {
            return WARNING;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "crit", "fatal", "error", "high", "page", "p1", "sev1" -> CRITICAL;
            case "info", "informational", "low", "none", "p4", "p5", "sev4", "sev5" -> INFO;
            default -> WARNING;
        };
    }
}

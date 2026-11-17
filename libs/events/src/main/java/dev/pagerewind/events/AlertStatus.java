package dev.pagerewind.events;

import java.util.Locale;

public enum AlertStatus {
    FIRING,
    RESOLVED;

    /** Anything other than "resolved" counts as firing. */
    public static AlertStatus parse(String value) {
        if (value != null && value.trim().toLowerCase(Locale.ROOT).equals("resolved")) {
            return RESOLVED;
        }
        return FIRING;
    }
}

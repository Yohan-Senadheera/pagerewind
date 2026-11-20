package dev.pagerewind.security;

import org.springframework.security.core.Authentication;

/** Who is calling: a person signed in through Keycloak, or a PageRewind service acting for someone. */
public final class Callers {

    /** Keycloak names the user behind a client-credentials token {@code service-account-<client id>}. */
    static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private Callers() {
    }

    public static boolean isService(Authentication authentication) {
        return authentication != null && authentication.getName().startsWith(SERVICE_ACCOUNT_PREFIX);
    }

    /**
     * The name to record for an action. People are always recorded as themselves. A service may name
     * the person it acts for (for example, the person who tapped Acknowledge on a page).
     */
    public static String actor(Authentication authentication, String onBehalfOf) {
        if (isService(authentication) && onBehalfOf != null && !onBehalfOf.isBlank()) {
            return onBehalfOf.trim();
        }
        return authentication.getName();
    }
}

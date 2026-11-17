package dev.pagerewind.events;

/** Names shared by every service that publishes or consumes events. */
public final class Messaging {

    /** Topic exchange that carries every event. */
    public static final String EVENTS_EXCHANGE = "pagerewind.events";

    /** Direct exchange for messages that failed too many times. */
    public static final String DEAD_LETTER_EXCHANGE = "pagerewind.dlx";

    /** Message header that holds the {@link EventType} name. */
    public static final String EVENT_TYPE_HEADER = "pagerewind-event-type";

    private Messaging() {
    }
}

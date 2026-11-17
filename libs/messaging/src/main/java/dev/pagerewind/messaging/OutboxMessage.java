package dev.pagerewind.messaging;

import java.util.UUID;

/** One stored event waiting to be published. */
public record OutboxMessage(long id, UUID eventId, String eventType, String routingKey, String payload) {
}

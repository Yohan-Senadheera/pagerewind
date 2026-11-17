package dev.pagerewind.incident;

import dev.pagerewind.incident.OutboxRepository.OutboxMessage;

/** Sends one outbox message to the broker. Throws if the broker did not confirm it. */
public interface MessagePublisher {

    void publish(OutboxMessage message);
}

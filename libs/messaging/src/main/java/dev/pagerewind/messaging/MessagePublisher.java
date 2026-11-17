package dev.pagerewind.messaging;

/** Sends one outbox message to the broker. Throws if the broker did not confirm it. */
public interface MessagePublisher {

    void publish(OutboxMessage message);
}

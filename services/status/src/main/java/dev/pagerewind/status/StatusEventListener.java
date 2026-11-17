package dev.pagerewind.status;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.IncidentAcknowledged;
import dev.pagerewind.events.IncidentResolved;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.Messaging;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
class StatusEventListener {

    private static final Logger log = LoggerFactory.getLogger(StatusEventListener.class);

    private final StatusRepository repository;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;

    StatusEventListener(StatusRepository repository, TransactionTemplate transaction, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.transaction = transaction;
        this.json = json;
        this.clock = clock;
    }

    @RabbitListener(queues = "${pagerewind.status.events-queue:status.incident-events}")
    void onEvent(Message message) {
        try {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            EventType type = EventType.fromRoutingKey(routingKey)
                    .orElseThrow(() -> new IllegalArgumentException("Unexpected routing key " + routingKey));
            switch (type) {
                case INCIDENT_TRIGGERED -> {
                    IncidentTriggered e = json.readValue(message.getBody(), IncidentTriggered.class);
                    once(e.eventId(), now -> repository.triggered(e.incidentId(), e.service(), e.severity(), e.occurredAt(), now));
                }
                case INCIDENT_ACKNOWLEDGED -> {
                    IncidentAcknowledged e = json.readValue(message.getBody(), IncidentAcknowledged.class);
                    once(e.eventId(), now -> repository.acknowledged(e.incidentId(), now));
                }
                case INCIDENT_RESOLVED -> {
                    IncidentResolved e = json.readValue(message.getBody(), IncidentResolved.class);
                    once(e.eventId(), now -> repository.resolved(e.incidentId(), e.occurredAt(), now));
                }
                default -> throw new IllegalArgumentException("Unexpected event type " + type);
            }
        } catch (RuntimeException e) {
            log.warn("Handling incident event {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }

    private void once(UUID eventId, java.util.function.Consumer<Instant> work) {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            if (repository.markProcessed(eventId, now)) {
                work.accept(now);
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {

        @Bean
        Declarables statusTopology(StatusProperties properties) {
            TopicExchange events = ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
            DirectExchange deadLetters = ExchangeBuilder.directExchange(Messaging.DEAD_LETTER_EXCHANGE).durable(true).build();
            Queue queue = QueueBuilder.durable(properties.eventsQueue())
                    .quorum()
                    .deliveryLimit(properties.deliveryLimit())
                    .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(properties.eventsQueue())
                    .build();
            Queue deadLetterQueue = QueueBuilder.durable(properties.eventsDeadLetterQueue()).quorum().build();
            return new Declarables(events, deadLetters, queue, deadLetterQueue,
                    BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_TRIGGERED.routingKey()),
                    BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_ACKNOWLEDGED.routingKey()),
                    BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_RESOLVED.routingKey()),
                    BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(properties.eventsQueue()));
        }
    }
}

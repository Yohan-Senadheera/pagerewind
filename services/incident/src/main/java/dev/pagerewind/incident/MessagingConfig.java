package dev.pagerewind.incident;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.Messaging;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues owned by this service. Quorum queues count deliveries, so a message that keeps failing is
 * moved to the dead-letter queue by the broker instead of looping forever.
 */
@Configuration
class MessagingConfig {

    private final IncidentProperties properties;

    MessagingConfig(IncidentProperties properties) {
        this.properties = properties;
    }

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(Messaging.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue alertsQueue() {
        return QueueBuilder.durable(properties.alertsQueue())
                .quorum()
                .deliveryLimit(properties.deliveryLimit())
                .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(properties.alertsQueue())
                .build();
    }

    @Bean
    Queue alertsDeadLetterQueue() {
        return QueueBuilder.durable(properties.alertsDeadLetterQueue()).quorum().build();
    }

    @Bean
    Binding alertsBinding() {
        return BindingBuilder.bind(alertsQueue()).to(eventsExchange()).with(EventType.ALERT_RECEIVED.routingKey());
    }

    @Bean
    Binding alertsDeadLetterBinding() {
        return BindingBuilder.bind(alertsDeadLetterQueue()).to(deadLetterExchange()).with(properties.alertsQueue());
    }

    @Bean
    Queue evidenceQueue() {
        return QueueBuilder.durable(properties.evidenceQueue())
                .quorum()
                .deliveryLimit(properties.deliveryLimit())
                .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(properties.evidenceQueue())
                .build();
    }

    @Bean
    Queue evidenceDeadLetterQueue() {
        return QueueBuilder.durable(properties.evidenceDeadLetterQueue()).quorum().build();
    }

    @Bean
    Binding evidenceBinding() {
        return BindingBuilder.bind(evidenceQueue()).to(eventsExchange()).with(EventType.EVIDENCE_CAPTURED.routingKey());
    }

    @Bean
    Binding evidenceDeadLetterBinding() {
        return BindingBuilder.bind(evidenceDeadLetterQueue()).to(deadLetterExchange()).with(properties.evidenceQueue());
    }
}

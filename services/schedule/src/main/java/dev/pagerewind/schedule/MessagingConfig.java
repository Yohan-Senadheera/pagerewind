package dev.pagerewind.schedule;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.Messaging;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MessagingConfig {

    @Bean
    Declarables scheduleTopology(ScheduleProperties properties) {
        TopicExchange events = ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
        DirectExchange deadLetters = ExchangeBuilder.directExchange(Messaging.DEAD_LETTER_EXCHANGE).durable(true).build();
        Queue queue = QueueBuilder.durable(properties.eventsQueue())
                .quorum()
                .deliveryLimit(properties.deliveryLimit())
                .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(properties.eventsQueue())
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(properties.eventsDeadLetterQueue()).quorum().build();
        Binding triggered = BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_TRIGGERED.routingKey());
        Binding acknowledged = BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_ACKNOWLEDGED.routingKey());
        Binding resolved = BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_RESOLVED.routingKey());
        Binding deadLetter = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(properties.eventsQueue());
        return new Declarables(events, deadLetters, queue, deadLetterQueue, triggered, acknowledged, resolved, deadLetter);
    }
}

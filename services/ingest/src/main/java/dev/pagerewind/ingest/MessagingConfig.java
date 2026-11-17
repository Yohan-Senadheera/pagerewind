package dev.pagerewind.ingest;

import dev.pagerewind.events.Messaging;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MessagingConfig {

    /** Declared by every service that uses it; declaring the same exchange twice is harmless. */
    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
    }
}

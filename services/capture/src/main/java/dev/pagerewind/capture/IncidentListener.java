package dev.pagerewind.capture;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.Messaging;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Captures evidence for every new incident. Failed messages are retried, then dead-lettered. */
@Component
class IncidentListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentListener.class);

    private final EvidenceService evidence;
    private final JsonMapper json;

    IncidentListener(EvidenceService evidence, JsonMapper json) {
        this.evidence = evidence;
        this.json = json;
    }

    @RabbitListener(queues = "${pagerewind.capture.incidents-queue:capture.incident-events}")
    void onIncident(Message message) {
        try {
            evidence.capture(json.readValue(message.getBody(), IncidentTriggered.class));
        } catch (RuntimeException e) {
            log.warn("Capturing evidence for message {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {

        @Bean
        Declarables captureTopology(@Value("${pagerewind.capture.incidents-queue:capture.incident-events}") String queueName,
                @Value("${pagerewind.capture.delivery-limit:4}") int deliveryLimit) {
            TopicExchange events = ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
            DirectExchange deadLetters = ExchangeBuilder.directExchange(Messaging.DEAD_LETTER_EXCHANGE).durable(true).build();
            Queue queue = QueueBuilder.durable(queueName).quorum().deliveryLimit(deliveryLimit)
                    .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(queueName).build();
            Queue deadLetterQueue = QueueBuilder.durable(queueName + ".dlq").quorum().build();
            return new Declarables(events, deadLetters, queue, deadLetterQueue,
                    BindingBuilder.bind(queue).to(events).with(EventType.INCIDENT_TRIGGERED.routingKey()),
                    BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(queueName));
        }
    }
}

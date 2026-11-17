package dev.pagerewind.notify;

import dev.pagerewind.events.EventType;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.events.PageRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
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
import tools.jackson.databind.json.JsonMapper;

@Component
class PageListener {

    private static final Logger log = LoggerFactory.getLogger(PageListener.class);

    private final PageService service;
    private final JsonMapper json;
    private final Counter failures;

    PageListener(PageService service, JsonMapper json, MeterRegistry meters) {
        this.service = service;
        this.json = json;
        this.failures = Counter.builder("pagerewind.notify.pages.failed")
                .description("Attempts to handle a page message that failed").register(meters);
    }

    @RabbitListener(queues = "${pagerewind.notify.pages-queue:notify.pages}")
    void onPage(Message message) {
        try {
            service.handle(json.readValue(message.getBody(), PageRequested.class));
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Handling page message {} failed: {}", message.getMessageProperties().getMessageId(), e.toString());
            throw e;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {

        @Bean
        Declarables notifyTopology(NotifyProperties properties) {
            TopicExchange events = ExchangeBuilder.topicExchange(Messaging.EVENTS_EXCHANGE).durable(true).build();
            DirectExchange deadLetters = ExchangeBuilder.directExchange(Messaging.DEAD_LETTER_EXCHANGE).durable(true).build();
            Queue queue = QueueBuilder.durable(properties.pagesQueue())
                    .quorum()
                    .deliveryLimit(properties.deliveryLimit())
                    .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(properties.pagesQueue())
                    .build();
            Queue deadLetterQueue = QueueBuilder.durable(properties.pagesDeadLetterQueue()).quorum().build();
            Binding pages = BindingBuilder.bind(queue).to(events).with(EventType.PAGE_REQUESTED.routingKey());
            Binding deadLetter = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(properties.pagesQueue());
            return new Declarables(events, deadLetters, queue, deadLetterQueue, pages, deadLetter);
        }
    }
}

package dev.pagerewind.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Outbox, relay and processed-event beans for any service that has the matching tables. */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxRepository outboxRepository(JdbcClient jdbc, JsonMapper json) {
        return new OutboxRepository(jdbc, json);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEventRepository processedEventRepository(JdbcClient jdbc) {
        return new ProcessedEventRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    RabbitMessagePublisher rabbitMessagePublisher(RabbitTemplate rabbit, MessagingProperties properties) {
        return new RabbitMessagePublisher(rabbit, properties.publishTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(OutboxRepository outbox, MessagePublisher publisher, TransactionTemplate transaction,
            MessagingProperties properties, Clock clock, MeterRegistry meters) {
        return new OutboxRelay(outbox, publisher, transaction, properties.outbox().batchSize(), clock, meters);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "pagerewind.messaging.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    static class RelaySchedule {

        private static final Logger log = LoggerFactory.getLogger(RelaySchedule.class);

        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${pagerewind.messaging.outbox.poll-interval-ms:500}")
        void relay() {
            try {
                while (relay.relayOnce() == relay.batchSize()) {
                    // A full batch means more rows may be waiting.
                }
            } catch (RuntimeException e) {
                log.warn("Outbox relay run failed: {}", e.toString());
            }
        }
    }
}

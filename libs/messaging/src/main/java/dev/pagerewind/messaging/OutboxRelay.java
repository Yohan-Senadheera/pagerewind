package dev.pagerewind.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to RabbitMQ.
 *
 * <p>Delivery is at least once: if the process dies after the broker confirmed a message but
 * before {@code published_at} was committed, that message is sent again on the next run. Consumers
 * are idempotent by {@code eventId}, so this is safe. Rows are published in order; the batch stops
 * at the first failure and the remaining rows wait for the next run.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final MessagePublisher publisher;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository outbox, MessagePublisher publisher, TransactionTemplate transaction,
            int batchSize, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = transaction;
        this.batchSize = batchSize;
        this.clock = clock;
        this.published = Counter.builder("pagerewind.outbox.published").description("Outbox events published").register(meters);
        this.failed = Counter.builder("pagerewind.outbox.failures").description("Outbox publish failures").register(meters);
        Gauge.builder("pagerewind.outbox.pending", outbox, OutboxRepository::countUnpublished)
                .description("Outbox events waiting to be published")
                .register(meters);
    }

    public int batchSize() {
        return batchSize;
    }

    /** Publishes up to one batch. Returns how many rows were published. */
    public int relayOnce() {
        Integer count = transaction.execute(status -> {
            List<OutboxMessage> batch = outbox.lockUnpublished(batchSize);
            int done = 0;
            for (OutboxMessage message : batch) {
                try {
                    publisher.publish(message);
                } catch (RuntimeException e) {
                    failed.increment();
                    outbox.markFailed(message.id(), e.toString());
                    log.warn("Publishing outbox event {} failed, will retry: {}", message.eventId(), e.toString());
                    break;
                }
                outbox.markPublished(message.id(), clock.instant());
                done++;
            }
            return done;
        });
        int done = count == null ? 0 : count;
        published.increment(done);
        return done;
    }
}

package dev.pagerewind.incident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.IncidentTriggered;
import dev.pagerewind.events.Messaging;
import dev.pagerewind.messaging.MessagePublisher;
import dev.pagerewind.messaging.OutboxMessage;
import dev.pagerewind.messaging.OutboxRelay;
import dev.pagerewind.messaging.OutboxRepository;
import dev.pagerewind.messaging.RabbitMessagePublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIT extends IntegrationTest {

    private static final String QUEUE = "it.incident.events";

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private RabbitMessagePublisher realPublisher;
    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void bindTestQueue() {
        admin.declareQueue(new Queue(QUEUE, false, false, false));
        admin.declareBinding(BindingBuilder.bind(new Queue(QUEUE)).to(new TopicExchange(Messaging.EVENTS_EXCHANGE)).with("incident.#"));
        admin.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void deleteTestQueue() {
        admin.deleteQueue(QUEUE);
    }

    private void openIncidents(int count) {
        for (int i = 0; i < count; i++) {
            service.handleAlert(alert(newFingerprint()));
        }
    }

    private OutboxRelay relayWith(MessagePublisher publisher) {
        return new OutboxRelay(outbox, publisher, transaction, 50, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    private List<Message> drainQueue() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(QUEUE, 1000)) != null) {
            messages.add(message);
        }
        return messages;
    }

    @Test
    void relayPublishesConfirmedEventsAndMarksThem() {
        openIncidents(1);
        UUID incidentId = jdbc.sql("SELECT id FROM incident").query(UUID.class).single();
        UUID eventId = jdbc.sql("SELECT event_id FROM outbox").query(UUID.class).single();

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        List<Message> messages = drainQueue();
        assertThat(messages).hasSize(1);
        Message message = messages.getFirst();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("incident.triggered");
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(message.getMessageProperties().<String>getHeader(Messaging.EVENT_TYPE_HEADER)).isEqualTo("INCIDENT_TRIGGERED");
        IncidentTriggered event = json.readValue(message.getBody(), IncidentTriggered.class);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.incidentId()).isEqualTo(incidentId);
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT attempts FROM outbox")).isEqualTo(1);
    }

    @Test
    void publisherDyingMidwayLosesNothing() {
        openIncidents(10);
        AtomicInteger calls = new AtomicInteger();
        MessagePublisher diesAfterFour = message -> {
            if (calls.incrementAndGet() > 4) {
                throw new IllegalStateException("connection lost");
            }
            realPublisher.publish(message);
        };

        assertThat(relayWith(diesAfterFour).relayOnce()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM outbox WHERE last_error LIKE '%connection lost%'")).isEqualTo(1);

        assertThat(relay.relayOnce()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM outbox WHERE last_error IS NOT NULL")).isZero();

        Set<String> published = new HashSet<>();
        for (Message message : drainQueue()) {
            published.add(message.getMessageProperties().getMessageId());
        }
        Set<String> expected = new HashSet<>(jdbc.sql("SELECT event_id::text FROM outbox").query(String.class).list());
        assertThat(published).hasSize(10).isEqualTo(expected);
    }

    @Test
    void crashAfterConfirmRepublishesWithTheSameEventId() {
        openIncidents(1);
        MessagePublisher crashesAfterSending = message -> {
            realPublisher.publish(message);
            throw new IllegalStateException("process killed before commit");
        };

        assertThat(relayWith(crashesAfterSending).relayOnce()).isZero();
        assertThat(relay.relayOnce()).isEqualTo(1);

        List<Message> messages = drainQueue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageProperties().getMessageId())
                .isEqualTo(messages.get(1).getMessageProperties().getMessageId());
    }

    @Test
    void concurrentRelaysPublishEveryRowExactlyOnce() throws Exception {
        openIncidents(120);
        List<UUID> published = Collections.synchronizedList(new ArrayList<>());
        MessagePublisher slowRecorder = message -> {
            published.add(message.eventId());
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        OutboxRelay first = relayWith(slowRecorder);
        OutboxRelay second = relayWith(slowRecorder);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> runs = new ArrayList<>();
        for (OutboxRelay r : List.of(first, second)) {
            runs.add(pool.submit(() -> {
                int total = 0;
                int done;
                while ((done = r.relayOnce()) > 0) {
                    total += done;
                }
                return total;
            }));
        }
        int total = 0;
        for (Future<Integer> run : runs) {
            total += run.get();
        }
        pool.shutdown();

        assertThat(total).isEqualTo(120);
        assertThat(published).hasSize(120).doesNotHaveDuplicates();
        assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
    }

    @Test
    void lockedRowsAreSkippedNotWaitedFor() {
        openIncidents(3);
        List<OutboxMessage> lockedByOther = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            lockedByOther.addAll(outbox.lockUnpublished(2));
            List<OutboxMessage> visibleToRelay = new ArrayList<>();
            Thread other = new Thread(() -> visibleToRelay.addAll(
                    transaction.execute(s -> outbox.lockUnpublished(50))));
            other.start();
            try {
                other.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(other.isAlive()).as("second reader must not block").isFalse();
            assertThat(visibleToRelay).hasSize(1);
            assertThat(visibleToRelay).extracting(OutboxMessage::id)
                    .doesNotContainAnyElementsOf(lockedByOther.stream().map(OutboxMessage::id).toList());
        });
        assertThat(lockedByOther).hasSize(2);
    }
}

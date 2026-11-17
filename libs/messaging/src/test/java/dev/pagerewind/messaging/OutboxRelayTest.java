package dev.pagerewind.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TransactionTemplate inline = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    private static OutboxMessage row(long id) {
        return new OutboxMessage(id, UUID.randomUUID(), "INCIDENT_TRIGGERED", "incident.triggered", "{}");
    }

    private OutboxRelay relay(MessagePublisher publisher) {
        return new OutboxRelay(outbox, publisher, inline, 50, Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    @Test
    void publishesInOrderAndMarksEachRow() {
        when(outbox.lockUnpublished(50)).thenReturn(List.of(row(1), row(2), row(3)));
        List<Long> sent = new ArrayList<>();

        int done = relay(m -> sent.add(m.id())).relayOnce();

        assertThat(done).isEqualTo(3);
        assertThat(sent).containsExactly(1L, 2L, 3L);
        verify(outbox).markPublished(1, NOW);
        verify(outbox).markPublished(2, NOW);
        verify(outbox).markPublished(3, NOW);
        assertThat(meters.counter("pagerewind.outbox.published").count()).isEqualTo(3);
    }

    @Test
    void stopsAtFirstFailureAndRecordsTheError() {
        when(outbox.lockUnpublished(50)).thenReturn(List.of(row(1), row(2), row(3)));

        int done = relay(m -> {
            if (m.id() == 2) {
                throw new IllegalStateException("broker down");
            }
        }).relayOnce();

        assertThat(done).isEqualTo(1);
        verify(outbox).markPublished(1, NOW);
        verify(outbox).markFailed(eq(2L), anyString());
        verify(outbox, never()).markPublished(eq(3L), eq(NOW));
        assertThat(meters.counter("pagerewind.outbox.failures").count()).isEqualTo(1);
    }

    @Test
    void emptyBatchDoesNothing() {
        when(outbox.lockUnpublished(50)).thenReturn(List.of());
        assertThat(relay(m -> {
            throw new AssertionError("must not publish");
        }).relayOnce()).isZero();
        verify(outbox, never()).markPublished(anyLong(), eq(NOW));
    }
}

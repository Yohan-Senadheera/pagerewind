package dev.pagerewind.notify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.notify.Retrier.PermanentFailure;
import dev.pagerewind.notify.Retrier.TransientFailure;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetrierTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final NotifyProperties.Retry settings = new NotifyProperties.Retry(4, Duration.ofMillis(100), Duration.ofMillis(500));
    private final Retrier retrier = new Retrier(settings, sleeps::add, new Random(42));

    @Test
    void successOnFirstTryDoesNotWait() {
        Retrier.Result result = retrier.run(() -> {
        });
        assertThat(result).isEqualTo(new Retrier.Result(true, 1, null));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void transientFailuresAreRetriedUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        Retrier.Result result = retrier.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new TransientFailure("503");
            }
        });
        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void permanentFailureStopsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        Retrier.Result result = retrier.run(() -> {
            calls.incrementAndGet();
            throw new PermanentFailure("400 chat not found");
        });
        assertThat(result).isEqualTo(new Retrier.Result(false, 1, "400 chat not found"));
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void givesUpAfterMaxAttemptsWithTheLastError() {
        AtomicInteger calls = new AtomicInteger();
        Retrier.Result result = retrier.run(() -> {
            throw new TransientFailure("timeout " + calls.incrementAndGet());
        });
        assertThat(result).isEqualTo(new Retrier.Result(false, 4, "timeout 4"));
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void backoffIsRandomButBoundedAndGrows() {
        for (int i = 0; i < 1000; i++) {
            assertThat(retrier.backoff(1, Duration.ZERO)).isBetween(0L, 100L);
            assertThat(retrier.backoff(2, Duration.ZERO)).isBetween(0L, 200L);
            assertThat(retrier.backoff(3, Duration.ZERO)).isBetween(0L, 400L);
            assertThat(retrier.backoff(4, Duration.ZERO)).isBetween(0L, 500L);
            assertThat(retrier.backoff(60, Duration.ZERO)).isBetween(0L, 500L);
        }
        long distinct = java.util.stream.IntStream.range(0, 50).mapToLong(i -> retrier.backoff(3, Duration.ZERO)).distinct().count();
        assertThat(distinct).as("jitter spreads waits out").isGreaterThan(10);
    }

    @Test
    void retryAfterFromTheServerIsRespectedUpToTheMaximum() {
        assertThat(retrier.backoff(1, Duration.ofMillis(300))).isBetween(300L, 500L);
        assertThat(retrier.backoff(1, Duration.ofSeconds(30))).isEqualTo(500L);
    }
}

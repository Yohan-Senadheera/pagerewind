package dev.pagerewind.notify;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Retries transient failures with exponential backoff and full jitter: before attempt n+1 it waits
 * a random time between 0 and {@code min(maxBackoff, initialBackoff * 2^(n-1))}. Jitter spreads retries
 * out so many senders do not hit a recovering service at the same moment.
 */
public class Retrier {

    /** Waits; replaced in tests so they do not really sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** A failure worth retrying (timeouts, 5xx, rate limits). */
    public static class TransientFailure extends RuntimeException {
        private final Duration retryAfter;

        public TransientFailure(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public TransientFailure(String message) {
            this(message, Duration.ZERO);
        }
    }

    /** A failure that will not go away by retrying (bad chat id, bad address). */
    public static class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }

    public record Result(boolean success, int attempts, String error) {
    }

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public Retrier(NotifyProperties.Retry settings, Sleeper sleeper, RandomGenerator random) {
        this.maxAttempts = settings.maxAttempts();
        this.initialBackoff = settings.initialBackoff();
        this.maxBackoff = settings.maxBackoff();
        this.sleeper = sleeper;
        this.random = random;
    }

    public Result run(Runnable action) {
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                return new Result(true, attempt, null);
            } catch (PermanentFailure e) {
                return new Result(false, attempt, e.getMessage());
            } catch (TransientFailure e) {
                lastError = e.getMessage();
                if (attempt < maxAttempts && !pause(backoff(attempt, e.retryAfter))) {
                    return new Result(false, attempt, "interrupted: " + lastError);
                }
            }
        }
        return new Result(false, maxAttempts, lastError);
    }

    /** Wait before the attempt after {@code attempt}. Never more than maxBackoff. */
    long backoff(int attempt, Duration retryAfter) {
        long cap = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() << Math.min(attempt - 1, 20));
        long jittered = cap <= 0 ? 0 : random.nextLong(cap + 1);
        return Math.min(maxBackoff.toMillis(), Math.max(jittered, retryAfter.toMillis()));
    }

    private boolean pause(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

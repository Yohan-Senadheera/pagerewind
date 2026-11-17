package dev.pagerewind.ingest;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fast check for "have we seen this fingerprint in the last few minutes?" using Redis
 * {@code SET key value NX EX}.
 *
 * <p>This is only an optimisation. The incident service has the real guarantee (a unique index),
 * so if Redis is down the filter fails open and every alert is treated as new.
 */
@Component
public class DuplicateFilter {

    static final String KEY_PREFIX = "pagerewind:ingest:seen:";
    private static final Logger log = LoggerFactory.getLogger(DuplicateFilter.class);

    private final StringRedisTemplate redis;
    private final IngestProperties properties;
    private final Counter redisErrors;

    public DuplicateFilter(StringRedisTemplate redis, IngestProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.redisErrors = Counter.builder("pagerewind.ingest.duplicate.filter.errors")
                .description("Redis errors in the duplicate filter (alerts were treated as new)")
                .register(meters);
    }

    /** Returns the same alert with {@code repeat} set. */
    public AlertReceived mark(AlertReceived alert) {
        String key = KEY_PREFIX + alert.fingerprint();
        try {
            if (alert.status() == AlertStatus.RESOLVED) {
                redis.delete(key);
                return alert;
            }
            Boolean firstTime = redis.opsForValue().setIfAbsent(key, "1", properties.duplicateWindow());
            boolean repeat = Boolean.FALSE.equals(firstTime);
            return repeat ? withRepeat(alert) : alert;
        } catch (RuntimeException e) {
            redisErrors.increment();
            log.warn("Duplicate filter unavailable, treating alert as new: {}", e.toString());
            return alert;
        }
    }

    private static AlertReceived withRepeat(AlertReceived a) {
        return new AlertReceived(a.eventId(), a.occurredAt(), a.version(), a.source(), a.service(), a.severity(),
                a.status(), a.summary(), a.labels(), a.fingerprint(), true, a.startsAt());
    }
}

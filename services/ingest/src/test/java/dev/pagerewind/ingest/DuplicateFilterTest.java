package dev.pagerewind.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pagerewind.events.AlertReceived;
import dev.pagerewind.events.AlertStatus;
import dev.pagerewind.events.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DuplicateFilterTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DuplicateFilter filter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        IngestProperties properties = new IngestProperties(Map.of(), WINDOW, Duration.ofSeconds(5), 1024, 10);
        filter = new DuplicateFilter(redis, properties, meters);
    }

    private static AlertReceived alert(AlertStatus status) {
        Instant now = Instant.now();
        return new AlertReceived(UUID.randomUUID(), now, 1, "test", "api", Severity.WARNING, status, "s",
                Map.of("a", "b"), "test:fp", false, now);
    }

    @Test
    void firstSightingIsNotARepeat() {
        when(values.setIfAbsent("pagerewind:ingest:seen:test:fp", "1", WINDOW)).thenReturn(true);
        AlertReceived in = alert(AlertStatus.FIRING);

        AlertReceived out = filter.mark(in);

        assertThat(out.repeat()).isFalse();
        assertThat(out).isEqualTo(in);
    }

    @Test
    void secondSightingIsARepeatAndKeepsEverythingElse() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AlertReceived in = alert(AlertStatus.FIRING);

        AlertReceived out = filter.mark(in);

        assertThat(out.repeat()).isTrue();
        assertThat(out.eventId()).isEqualTo(in.eventId());
        assertThat(out.fingerprint()).isEqualTo(in.fingerprint());
        assertThat(out.labels()).isEqualTo(in.labels());
    }

    @Test
    void resolvedAlertClearsTheKeyAndIsNeverARepeat() {
        AlertReceived out = filter.mark(alert(AlertStatus.RESOLVED));

        assertThat(out.repeat()).isFalse();
        verify(redis).delete("pagerewind:ingest:seen:test:fp");
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureFailsOpenAndIsCounted() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        AlertReceived out = filter.mark(alert(AlertStatus.FIRING));

        assertThat(out.repeat()).isFalse();
        assertThat(meters.counter("pagerewind.ingest.duplicate.filter.errors").count()).isEqualTo(1.0);
    }

    @Test
    void nullReplyFromRedisIsNotARepeat() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThat(filter.mark(alert(AlertStatus.FIRING)).repeat()).isFalse();
    }
}

package dev.pagerewind.notify;

import dev.pagerewind.events.Contact;
import dev.pagerewind.events.PageRequested;
import dev.pagerewind.notify.DeliveryRepository.Delivery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers one {@link PageRequested} to every contact of the person.
 *
 * <ol>
 *   <li>In one transaction, write a delivery row per contact (or RATE_LIMITED if the person was
 *       paged for this incident on that channel a moment ago). Existing rows mean the event was
 *       seen before, so nothing is added.</li>
 *   <li>For each PENDING row: claim it, send with retries, store SENT or FAILED.</li>
 * </ol>
 *
 * A crash between sending and storing the result means the page may be sent twice when the event is
 * redelivered. For paging that is the right trade-off: a duplicate page is better than a lost one.
 */
@Service
public class PageService {

    private static final Logger log = LoggerFactory.getLogger(PageService.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final DeliveryRepository deliveries;
    private final Senders senders;
    private final TransactionTemplate transaction;
    private final NotifyProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Retrier retrier;

    public PageService(DeliveryRepository deliveries, Senders senders, TransactionTemplate transaction,
            NotifyProperties properties, Clock clock, MeterRegistry meters, Retrier.Sleeper sleeper) {
        this.deliveries = deliveries;
        this.senders = senders;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        // java.util.Random is in java.base. RandomGenerator.getDefault() needs the jdk.random module,
        // which slim JRE images (like the one in our Dockerfile) do not include.
        this.retrier = new Retrier(properties.retry(), sleeper, new Random());
    }

    public void handle(PageRequested page) {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            for (Contact contact : page.contacts()) {
                if (deliveries.exists(page.eventId(), contact)) {
                    continue;
                }
                boolean limited = deliveries.pagedRecently(page.incidentId(), page.personId(), contact.channel(),
                        page.eventId(), now, properties.rateLimit());
                deliveries.insertIfAbsent(page.eventId(), page.incidentId(), page.personId(), contact, page.step(),
                        limited ? "RATE_LIMITED" : "PENDING", now);
                if (limited) {
                    count(contact.channel(), "rate_limited");
                }
            }
        });

        for (Delivery delivery : deliveries.forEvent(page.eventId())) {
            if (!delivery.status().equals("PENDING") || !deliveries.claim(delivery.id(), clock.instant(), CLAIM_LEASE)) {
                continue;
            }
            Contact contact = new Contact(delivery.channel(), delivery.address());
            Retrier.Result result = retrier.run(() -> senders.send(page, contact));
            deliveries.finish(delivery.id(), result.success(), result.attempts(), result.error(), clock.instant());
            if (result.success()) {
                count(delivery.channel(), "sent");
            } else {
                count(delivery.channel(), "failed");
                log.warn("Page for incident {} to {} via {} failed after {} attempts: {}", page.incidentId(),
                        page.personId(), delivery.channel(), result.attempts(), result.error());
            }
        }
    }

    private void count(Contact.Channel channel, String outcome) {
        Counter.builder("pagerewind.notify.deliveries").description("Page deliveries by channel and outcome")
                .tag("channel", channel.name().toLowerCase()).tag("outcome", outcome)
                .register(meters).increment();
    }
}

package dev.pagerewind.notify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.events.Contact;
import dev.pagerewind.events.PageRequested;
import dev.pagerewind.events.Severity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageMessagesTest {

    private static PageRequested page(int round) {
        return new PageRequested(UUID.randomUUID(), Instant.now(), 1, UUID.randomUUID(), 2, round, "alice", "Alice",
                List.of(new Contact(Contact.Channel.EMAIL, "a@example.com")), "checkout", Severity.CRITICAL,
                "Checkout 5xx above 5%");
    }

    @Test
    void telegramTextNamesSeverityServiceSummaryAndPerson() {
        PageRequested p = page(1);
        String text = PageMessages.telegramText(p);
        assertThat(text).contains("CRITICAL · checkout").contains("Checkout 5xx above 5%")
                .contains("Paged: Alice (step 2)").contains(p.incidentId().toString())
                .doesNotContain("round");
        assertThat(PageMessages.telegramText(page(3))).contains("(step 2, round 3)");
    }

    @Test
    void callbackDataFitsTelegramsSixtyFourByteLimit() {
        UUID id = UUID.randomUUID();
        assertThat(PageMessages.callbackData(PageMessages.ACK_PREFIX, id).getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(64);
        assertThat(PageMessages.callbackData(PageMessages.RESOLVE_PREFIX, id)).isEqualTo("res:" + id);
    }

    @Test
    void emailHasSubjectLinksAndExpiry() {
        PageRequested p = page(1);
        assertThat(PageMessages.emailSubject(p)).isEqualTo("[PageRewind] CRITICAL checkout: Checkout 5xx above 5%");
        String body = PageMessages.emailBody(p, "https://x/ack", "https://x/res", 30);
        assertThat(body).contains("Acknowledge: https://x/ack").contains("Resolve: https://x/res")
                .contains("expires in 30 minutes").contains("step 2");
    }

    @Test
    void emailWithoutLinksStillDescribesTheIncident() {
        String body = PageMessages.emailBody(page(2), null, null, 30);
        assertThat(body).contains("Checkout 5xx above 5%").contains("round 2").doesNotContain("Acknowledge:");
    }
}

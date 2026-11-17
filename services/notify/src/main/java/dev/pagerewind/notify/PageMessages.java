package dev.pagerewind.notify;

import dev.pagerewind.events.PageRequested;
import java.util.UUID;

/** Text of pages. Plain text only, so nothing from an alert can inject formatting or markup. */
final class PageMessages {

    static final String ACK_PREFIX = "ack:";
    static final String RESOLVE_PREFIX = "res:";

    private PageMessages() {
    }

    static String telegramText(PageRequested page) {
        return """
                %s %s · %s
                %s

                Paged: %s (step %d%s)
                Incident: %s""".formatted(icon(page), page.severity(), page.service(), page.summary(),
                page.personName(), page.step(), page.round() > 1 ? ", round " + page.round() : "", page.incidentId());
    }

    static String emailSubject(PageRequested page) {
        return "[PageRewind] %s %s: %s".formatted(page.severity(), page.service(), page.summary());
    }

    static String emailBody(PageRequested page, String acknowledgeUrl, String resolveUrl, long ttlMinutes) {
        StringBuilder body = new StringBuilder();
        body.append(page.severity()).append(" incident on ").append(page.service()).append("\n\n")
                .append(page.summary()).append("\n\n")
                .append("You are paged as step ").append(page.step());
        if (page.round() > 1) {
            body.append(", round ").append(page.round());
        }
        body.append(".\nIncident: ").append(page.incidentId()).append("\n\n");
        if (acknowledgeUrl != null) {
            body.append("Acknowledge: ").append(acknowledgeUrl).append("\n")
                    .append("Resolve: ").append(resolveUrl).append("\n\n")
                    .append("Each link works once and expires in ").append(ttlMinutes).append(" minutes.\n");
        }
        return body.toString();
    }

    static String callbackData(String prefix, UUID incidentId) {
        return prefix + incidentId;
    }

    private static String icon(PageRequested page) {
        return switch (page.severity()) {
            case CRITICAL -> "🔴";
            case WARNING -> "🟠";
            case INFO -> "🔵";
        };
    }
}

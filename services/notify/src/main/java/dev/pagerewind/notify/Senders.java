package dev.pagerewind.notify;

import dev.pagerewind.events.Contact;
import dev.pagerewind.events.PageRequested;
import dev.pagerewind.notify.Retrier.PermanentFailure;
import dev.pagerewind.notify.Retrier.TransientFailure;
import java.util.List;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends one page to one contact. Throws {@link TransientFailure} or {@link PermanentFailure}. */
@Component
public class Senders {

    private final TelegramClient telegram;
    private final JavaMailSender mail;
    private final ActionTokens tokens;
    private final NotifyProperties properties;

    public Senders(TelegramClient telegram, JavaMailSender mail, ActionTokens tokens, NotifyProperties properties) {
        this.telegram = telegram;
        this.mail = mail;
        this.tokens = tokens;
        this.properties = properties;
    }

    public void send(PageRequested page, Contact contact) {
        switch (contact.channel()) {
            case TELEGRAM -> sendTelegram(page, contact.address());
            case EMAIL -> sendEmail(page, contact.address());
        }
    }

    private void sendTelegram(PageRequested page, String chatId) {
        if (!telegram.enabled()) {
            throw new PermanentFailure("Telegram is not configured");
        }
        telegram.sendMessage(chatId, PageMessages.telegramText(page), List.of(
                new TelegramClient.Button("Acknowledge", PageMessages.callbackData(PageMessages.ACK_PREFIX, page.incidentId())),
                new TelegramClient.Button("Resolve", PageMessages.callbackData(PageMessages.RESOLVE_PREFIX, page.incidentId()))));
    }

    private void sendEmail(PageRequested page, String address) {
        String ackUrl = null;
        String resolveUrl = null;
        if (tokens.enabled()) {
            String base = properties.publicBaseUrl().replaceAll("/+$", "") + "/v1/actions/";
            ackUrl = base + tokens.create(page.incidentId(), ActionTokens.Action.ACKNOWLEDGE, page.personId());
            resolveUrl = base + tokens.create(page.incidentId(), ActionTokens.Action.RESOLVE, page.personId());
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.email().from());
        message.setTo(address);
        message.setSubject(PageMessages.emailSubject(page));
        message.setText(PageMessages.emailBody(page, ackUrl, resolveUrl, properties.actionLinks().ttl().toMinutes()));
        try {
            mail.send(message);
        } catch (MailAuthenticationException | MailParseException e) {
            throw new PermanentFailure("Email to " + address + " failed: " + e.getMessage());
        } catch (MailException e) {
            throw new TransientFailure("Email to " + address + " failed: " + e.getMessage());
        }
    }
}

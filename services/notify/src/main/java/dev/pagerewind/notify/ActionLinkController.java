package dev.pagerewind.notify;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Email action links. Opening a link (GET) only shows a confirm button; the action happens on POST.
 * Mail scanners and link previews open links automatically, so a GET must never change anything.
 */
@RestController
public class ActionLinkController {

    private final ActionTokens tokens;
    private final DeliveryRepository deliveries;
    private final IncidentClient incidents;
    private final Clock clock;

    public ActionLinkController(ActionTokens tokens, DeliveryRepository deliveries, IncidentClient incidents, Clock clock) {
        this.tokens = tokens;
        this.deliveries = deliveries;
        this.incidents = incidents;
        this.clock = clock;
    }

    @GetMapping(value = "/v1/actions/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@PathVariable String token) {
        ActionTokens.Claims claims;
        try {
            claims = tokens.verify(token);
        } catch (ActionTokens.ExpiredToken e) {
            return page(HttpStatus.GONE, "Link expired", e.getMessage() + ". Open PageRewind to act on the incident.");
        } catch (ActionTokens.InvalidToken e) {
            return page(HttpStatus.BAD_REQUEST, "Invalid link", e.getMessage() + ".");
        }
        String verb = claims.action() == ActionTokens.Action.ACKNOWLEDGE ? "Acknowledge" : "Resolve";
        String body = "<p>" + verb + " incident <code>" + claims.incidentId() + "</code> as <strong>"
                + HtmlUtils.htmlEscape(claims.personId()) + "</strong>?</p>"
                + "<form method=\"post\"><button type=\"submit\">" + verb + "</button></form>";
        return html(HttpStatus.OK, verb + " incident", body);
    }

    @PostMapping(value = "/v1/actions/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> act(@PathVariable String token) {
        ActionTokens.Claims claims;
        try {
            claims = tokens.verify(token);
        } catch (ActionTokens.ExpiredToken e) {
            return page(HttpStatus.GONE, "Link expired", e.getMessage() + ".");
        } catch (ActionTokens.InvalidToken e) {
            return page(HttpStatus.BAD_REQUEST, "Invalid link", e.getMessage() + ".");
        }
        if (!deliveries.useNonce(claims.nonce(), clock.instant())) {
            return page(HttpStatus.CONFLICT, "Link already used", "Each link works once.");
        }
        IncidentClient.Result result = incidents.apply(claims.incidentId(), claims.action(), claims.personId());
        HttpStatus status = switch (result) {
            case DONE -> HttpStatus.OK;
            case ALREADY_DONE -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return page(status, "PageRewind", IncidentClient.describe(result, claims.action()));
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String message) {
        return html(status, title, "<p>" + HtmlUtils.htmlEscape(message) + "</p>");
    }

    private static ResponseEntity<String> html(HttpStatus status, String title, String body) {
        String document = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>%s</title>
                <style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem}
                button{font-size:1rem;padding:.6rem 1.2rem}</style></head>
                <body><h1>%s</h1>%s</body></html>""".formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), body);
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
                .contentType(MediaType.TEXT_HTML)
                .body(document);
    }
}

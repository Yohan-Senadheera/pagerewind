package dev.pagerewind.ingest;

import dev.pagerewind.ingest.EventPublisher.PublishFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns errors into RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptions {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptions.class);

    @ExceptionHandler(Unauthenticated.class)
    ResponseEntity<ProblemDetail> unauthenticated(Unauthenticated e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Send a valid bearer token or " + AlertController.SIGNATURE_HEADER + " header");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(PayloadTooLarge.class)
    ResponseEntity<ProblemDetail> tooLarge(PayloadTooLarge e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage()));
    }

    @ExceptionHandler(PublishFailedException.class)
    ResponseEntity<ProblemDetail> publishFailed(PublishFailedException e) {
        log.error("Could not publish alerts: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Alerts could not be stored right now. Retry later."));
    }

    static class Unauthenticated extends RuntimeException {
        Unauthenticated() {
            super("unauthenticated");
        }
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    static class PayloadTooLarge extends RuntimeException {
        PayloadTooLarge(int limit) {
            super("The body is larger than " + limit + " bytes");
        }
    }
}

package dev.pagerewind.incident;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Domain errors and how they map to RFC 9457 problem responses. */
@RestControllerAdvice
public class IncidentErrors {

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ProblemDetail> notFound(NotFound e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidTransition.class)
    ResponseEntity<ProblemDetail> invalidTransition(InvalidTransition e) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, e.getMessage());
        response.getBody().setProperty("currentStatus", e.current.name());
        return response;
    }

    @ExceptionHandler(ConcurrentChange.class)
    ResponseEntity<ProblemDetail> concurrentChange(ConcurrentChange e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    public static class NotFound extends RuntimeException {
        NotFound(UUID id) {
            super("Incident " + id + " does not exist");
        }
    }

    public static class InvalidTransition extends RuntimeException {
        final IncidentStatus current;

        InvalidTransition(UUID id, IncidentStatus current, IncidentStatus target) {
            super("Incident " + id + " is " + current + " and cannot become " + target);
            this.current = current;
        }
    }

    public static class ConcurrentChange extends RuntimeException {
        ConcurrentChange(UUID id) {
            super("Incident " + id + " was changed by someone else. Reload it and try again.");
        }
    }
}

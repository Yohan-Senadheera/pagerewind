package dev.pagerewind.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ScheduleErrors {

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ProblemDetail> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ProblemDetail> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    public static class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    public static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}

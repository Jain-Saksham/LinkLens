package com.linklens.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 ProblemDetail responses. Extending ResponseEntityExceptionHandler
 * gives consistent bodies for Spring's own errors too (malformed JSON, wrong method, etc.).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ShortUrlNotFoundException.class)
    public ProblemDetail handleNotFound(ShortUrlNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Short URL not found", e.getMessage());
    }

    @ExceptionHandler(ShortUrlExpiredException.class)
    public ProblemDetail handleExpired(ShortUrlExpiredException e) {
        return problem(HttpStatus.GONE, "Short URL expired", e.getMessage());
    }

    @ExceptionHandler(AliasUnavailableException.class)
    public ProblemDetail handleAliasUnavailable(AliasUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "Alias unavailable", e.getMessage());
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid URL", e.getMessage());
    }

    /** Adds a field -> message map so clients can show errors next to the right input. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

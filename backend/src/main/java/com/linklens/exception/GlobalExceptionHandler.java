package com.linklens.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 ProblemDetail responses. Extending ResponseEntityExceptionHandler
 * gives consistent bodies for Spring's own errors too (malformed JSON, wrong method, etc.).
 * Infrastructure failures never leak internals (SQL, hostnames, stack traces) to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    static final String DB_RETRY_AFTER_SECONDS = "5";

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

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", e.getMessage()));
    }

    /** Database unreachable, pool exhausted, timeouts: temporary, so tell clients to retry. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception e) {
        log.error("Database unavailable: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, DB_RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
                        "Please try again shortly."));
    }

    /** Last resort: log the details, return nothing internal. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Something went wrong.");
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

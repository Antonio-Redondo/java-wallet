package com.wallet.controller;

import com.wallet.dto.ErrorResponse;
import com.wallet.exception.AccountNotFoundException;
import com.wallet.exception.CurrencyMismatchException;
import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.exception.InvalidTransferException;
import com.wallet.exception.OAuthTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Translates domain and validation exceptions into consistent HTTP responses.
 *
 * <p>Client errors (4xx) are logged at {@code WARN}/{@code DEBUG} so a noisy
 * caller cannot flood the logs at error level; only genuinely unexpected
 * failures (5xx) are logged at {@code ERROR} with a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException ex) {
        log.warn("Account not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(InvalidTransferException ex) {
        log.debug("Invalid transfer request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("Insufficient funds: {}", ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ErrorResponse> handleCurrencyMismatch(CurrencyMismatchException ex) {
        log.warn("Currency mismatch: {}", ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** OAuth 2.0 token-endpoint failures (bad grant, client or scope). */
    @ExceptionHandler(OAuthTokenException.class)
    public ResponseEntity<ErrorResponse> handleOAuth(OAuthTokenException ex) {
        log.warn("OAuth token request failed [{}]: {}", ex.getError(), ex.getMessage());
        return build(ex.getStatus(), ex.getError() + ": " + ex.getMessage());
    }

    /**
     * A unique-constraint violation on the idempotency key means two requests
     * with the same key raced; one won. Surface it as a conflict so the client
     * can safely retry (the retry will then find the committed transaction).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation (likely idempotency-key race): {}", ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "Concurrent request with the same idempotency key; please retry to fetch the result");
    }

    /** Bean-validation failures on request bodies (e.g. missing/invalid fields). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.debug("Request validation failed: {}", details);
        return build(HttpStatus.BAD_REQUEST, details.isBlank() ? "Validation failed" : details);
    }

    /**
     * Request to a path with no controller or static resource (e.g. a mistyped
     * URL or a stray {@code /favicon.ico}). This is a client 404, not a server
     * fault, so it is logged quietly and must not fall through to the catch-all
     * below (which would log it at ERROR and return 500).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource for path: {}", ex.getResourcePath());
        return build(HttpStatus.NOT_FOUND, "No such resource: " + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error: " + ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
    }
}

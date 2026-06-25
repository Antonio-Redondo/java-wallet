package com.wallet.exception;

/**
 * Thrown when an idempotency key is reused for a request whose parameters
 * differ from the original. Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey
                + "' was already used for a transfer with different parameters");
    }
}

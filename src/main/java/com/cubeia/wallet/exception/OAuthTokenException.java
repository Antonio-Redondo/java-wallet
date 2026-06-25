package com.cubeia.wallet.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request to the {@code /oauth/token} endpoint cannot be granted
 * (bad grant type, unknown client, unauthorized scope). Carries the standard
 * OAuth 2.0 {@code error} code (RFC 6749 §5.2) and the HTTP status to return.
 */
public class OAuthTokenException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public OAuthTokenException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    /** The OAuth 2.0 error code, e.g. {@code invalid_client}. */
    public String getError() {
        return error;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

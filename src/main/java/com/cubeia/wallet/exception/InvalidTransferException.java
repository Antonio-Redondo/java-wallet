package com.cubeia.wallet.exception;

/** Thrown when a transfer request is structurally invalid. Maps to HTTP 400. */
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}

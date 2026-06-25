package com.cubeia.wallet.exception;

import java.util.UUID;

/** Thrown when an account id does not exist. Maps to HTTP 404. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}

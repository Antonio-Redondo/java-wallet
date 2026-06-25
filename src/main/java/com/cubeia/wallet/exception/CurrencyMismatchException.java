package com.cubeia.wallet.exception;

import java.util.UUID;

/** Thrown when a transfer currency does not match an account currency. Maps to HTTP 422. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(UUID accountId, String accountCurrency, String requestedCurrency) {
        super("Currency mismatch for account " + accountId
                + ": account is " + accountCurrency + ", request used " + requestedCurrency);
    }
}

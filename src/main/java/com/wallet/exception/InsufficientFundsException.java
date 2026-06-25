package com.wallet.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a debit would make a balance negative. Maps to HTTP 422. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds in account " + accountId
                + ": balance " + balance + ", requested " + requested);
    }
}

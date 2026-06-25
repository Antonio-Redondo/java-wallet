package com.cubeia.wallet.dto;

import com.cubeia.wallet.model.Transaction;
import com.cubeia.wallet.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String idempotencyKey,
        TransactionType type,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        Instant timestamp) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getIdempotencyKey(),
                t.getType(),
                t.getFromAccountId(),
                t.getToAccountId(),
                t.getAmount(),
                t.getCurrency(),
                t.getTimestamp());
    }
}

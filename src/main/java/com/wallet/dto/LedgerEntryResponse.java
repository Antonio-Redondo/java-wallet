package com.wallet.dto;

import com.wallet.model.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transactionId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant timestamp) {

    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(),
                e.getTransactionId(),
                e.getAmount(),
                e.getBalanceAfter(),
                e.getTimestamp());
    }
}

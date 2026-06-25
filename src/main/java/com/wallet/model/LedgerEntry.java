package com.wallet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The immutable record of how a single transaction affected a single account.
 *
 * <p>This is what "list transactions for an account" returns. The {@code amount}
 * is signed: negative for a debit (money leaving the account) and positive for
 * a credit (money entering it). {@code balanceAfter} captures the account
 * balance immediately after this entry was applied, which gives an auditable
 * running balance and makes it trivial to verify that
 * {@code balance == sum(entry amounts)} at any time.
 *
 * <p>Ledger entries are append-only; they are never updated or deleted.
 */
@Entity
@Table(
        name = "ledger_entries",
        indexes = @Index(name = "idx_ledger_account", columnList = "account_id"))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    /** Required by JPA. */
    protected LedgerEntry() {
    }

    public LedgerEntry(UUID accountId, UUID transactionId, BigDecimal amount, BigDecimal balanceAfter) {
        this.accountId = accountId;
        this.transactionId = transactionId;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.timestamp = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}

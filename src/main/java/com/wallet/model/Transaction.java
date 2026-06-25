package com.wallet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single completed money movement.
 *
 * <p>A transaction is the "header" record; the actual per-account effects are
 * recorded as {@link LedgerEntry} rows that reference this transaction's id.
 * A {@code TRANSFER} produces two ledger entries (a debit and a credit), while
 * a {@code DEPOSIT} or {@code WITHDRAWAL} produces one.
 *
 * <p>The optional {@code idempotencyKey} lets a client safely retry a request
 * (e.g. after a network timeout) without applying it twice. It is protected by
 * a unique constraint so that even two concurrent requests carrying the same
 * key can only ever create one transaction.
 */
@Entity
@Table(
        name = "transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_transaction_idempotency_key",
                columnNames = "idempotency_key"))
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(name = "from_account_id")
    private UUID fromAccountId;

    @Column(name = "to_account_id")
    private UUID toAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    /** Required by JPA. */
    protected Transaction() {
    }

    public Transaction(String idempotencyKey,
                       TransactionType type,
                       UUID fromAccountId,
                       UUID toAccountId,
                       BigDecimal amount,
                       String currency) {
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency.toUpperCase();
        this.timestamp = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionType getType() {
        return type;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}

package com.cubeia.wallet.model;

import com.cubeia.wallet.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An account holds a single-currency balance.
 *
 * <p>Money is stored as {@link BigDecimal} - never floating point - so that
 * monetary arithmetic is exact. The balance is mutated only through the
 * {@link #credit(BigDecimal)} and {@link #debit(BigDecimal)} methods, which
 * keep the invariant that a balance can never become negative.
 *
 * <p>This entity is not thread-safe on its own. Safe concurrent mutation is
 * guaranteed at the service layer, which loads the row under a pessimistic
 * database lock before mutating it (see {@code WalletService}).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Account() {
    }

    public Account(String currency) {
        this.currency = currency.toUpperCase();
        this.balance = BigDecimal.ZERO.setScale(4);
        this.createdAt = Instant.now();
    }

    /** Increase the balance. Caller is responsible for validating the amount is positive. */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /**
     * Decrease the balance.
     *
     * @throws InsufficientFundsException if the balance would become negative.
     */
    public void debit(BigDecimal amount) {
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(this.id, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public UUID getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

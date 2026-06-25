package com.wallet.model;

/**
 * The kind of money movement a {@link Transaction} represents.
 *
 * <ul>
 *   <li>{@code DEPOSIT}    - funds enter an account from outside the system (only a "to" account).</li>
 *   <li>{@code WITHDRAWAL} - funds leave an account to outside the system (only a "from" account).</li>
 *   <li>{@code TRANSFER}   - funds move between two internal accounts.</li>
 * </ul>
 */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}

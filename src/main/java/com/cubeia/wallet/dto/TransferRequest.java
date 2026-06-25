package com.cubeia.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for a transfer.
 *
 * <p>The combination of {@code fromAccountId} / {@code toAccountId} determines
 * the kind of movement:
 * <ul>
 *   <li>both present  -&gt; transfer between two accounts</li>
 *   <li>only "to"     -&gt; deposit</li>
 *   <li>only "from"   -&gt; withdrawal</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} is optional. Supplying a stable key makes the
 * request safe to retry: a repeat with the same key returns the original
 * result instead of moving money twice.
 */
public record TransferRequest(
        String idempotencyKey,
        UUID fromAccountId,
        UUID toAccountId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}

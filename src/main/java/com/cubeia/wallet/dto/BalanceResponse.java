package com.cubeia.wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(UUID accountId, String currency, BigDecimal balance) {
}

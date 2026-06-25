package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for creating an account. */
public record CreateAccountRequest(
        @NotBlank @Size(min = 3, max = 3) String currency) {
}

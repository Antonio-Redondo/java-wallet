package com.cubeia.wallet.controller;

import com.cubeia.wallet.dto.TransactionResponse;
import com.cubeia.wallet.dto.TransferRequest;
import com.cubeia.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
@Tag(name = "Transfers", description = "Move money: deposit, withdraw or transfer between accounts")
public class TransferController {

    private final WalletService walletService;

    public TransferController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Operation(
            summary = "Move funds (deposit / withdrawal / transfer)",
            description = """
                    The operation is inferred from the account ids supplied:
                    both -> transfer, only `toAccountId` -> deposit, only `fromAccountId` -> withdrawal.
                    Supply an optional `idempotencyKey` to make the request safe to retry.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Money movement applied"),
            @ApiResponse(responseCode = "400", description = "Malformed or invalid request", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "Account not found", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with different parameters", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "422", description = "Insufficient funds or currency mismatch", content = @io.swagger.v3.oas.annotations.media.Content)})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(@Valid @RequestBody TransferRequest request) {
        return TransactionResponse.from(walletService.transfer(request));
    }
}

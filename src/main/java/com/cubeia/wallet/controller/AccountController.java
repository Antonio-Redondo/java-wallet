package com.cubeia.wallet.controller;

import com.cubeia.wallet.dto.AccountResponse;
import com.cubeia.wallet.dto.BalanceResponse;
import com.cubeia.wallet.dto.CreateAccountRequest;
import com.cubeia.wallet.dto.LedgerEntryResponse;
import com.cubeia.wallet.model.Account;
import com.cubeia.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Create accounts and read balances and ledger history")
public class AccountController {

    private final WalletService walletService;

    public AccountController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Operation(summary = "Create a new single-currency account")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = walletService.createAccount(request.currency());
        return AccountResponse.from(account);
    }

    @Operation(summary = "Get the full account record")
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(walletService.getAccount(id));
    }

    @Operation(summary = "Get the current balance for an account")
    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        Account account = walletService.getAccount(id);
        return new BalanceResponse(account.getId(), account.getCurrency(), account.getBalance());
    }

    @Operation(summary = "List the ledger entries (signed transaction effects) for an account, oldest first")
    @GetMapping("/{id}/transactions")
    public List<LedgerEntryResponse> transactions(@PathVariable UUID id) {
        return walletService.listTransactions(id).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}

package com.cubeia.wallet;

import com.cubeia.wallet.dto.TransferRequest;
import com.cubeia.wallet.exception.CurrencyMismatchException;
import com.cubeia.wallet.exception.IdempotencyConflictException;
import com.cubeia.wallet.exception.InsufficientFundsException;
import com.cubeia.wallet.exception.InvalidTransferException;
import com.cubeia.wallet.model.Account;
import com.cubeia.wallet.model.LedgerEntry;
import com.cubeia.wallet.model.Transaction;
import com.cubeia.wallet.model.TransactionType;
import com.cubeia.wallet.repository.AccountRepository;
import com.cubeia.wallet.repository.LedgerEntryRepository;
import com.cubeia.wallet.repository.TransactionRepository;
import com.cubeia.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class WalletServiceTest {

    @Autowired
    private WalletService walletService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void newAccountStartsAtZero() {
        Account account = walletService.createAccount("EUR");
        assertThat(walletService.getAccount(account.getId()).getBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void depositIncreasesBalanceAndRecordsEntry() {
        Account account = walletService.createAccount("EUR");

        Transaction tx = walletService.transfer(new TransferRequest(
                null, null, account.getId(), new BigDecimal("100.00"), "EUR"));

        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(walletService.getAccount(account.getId()).getBalance())
                .isEqualByComparingTo("100.00");

        List<LedgerEntry> entries = walletService.listTransactions(account.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(entries.get(0).getBalanceAfter()).isEqualByComparingTo("100.00");
    }

    @Test
    void transferMovesFundsBetweenAccounts() {
        Account a = walletService.createAccount("EUR");
        Account b = walletService.createAccount("EUR");
        deposit(a, "100.00");

        walletService.transfer(new TransferRequest(
                null, a.getId(), b.getId(), new BigDecimal("30.00"), "EUR"));

        assertThat(walletService.getAccount(a.getId()).getBalance()).isEqualByComparingTo("70.00");
        assertThat(walletService.getAccount(b.getId()).getBalance()).isEqualByComparingTo("30.00");

        // The transfer produced exactly one entry on each side.
        assertThat(walletService.listTransactions(a.getId())).hasSize(2); // deposit + transfer out
        assertThat(walletService.listTransactions(b.getId())).hasSize(1); // transfer in
    }

    @Test
    void withdrawalReducesBalance() {
        Account a = walletService.createAccount("EUR");
        deposit(a, "50.00");

        walletService.transfer(new TransferRequest(
                null, a.getId(), null, new BigDecimal("20.00"), "EUR"));

        assertThat(walletService.getAccount(a.getId()).getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void overdraftIsRejected() {
        Account a = walletService.createAccount("EUR");
        deposit(a, "10.00");

        assertThatThrownBy(() -> walletService.transfer(new TransferRequest(
                null, a.getId(), null, new BigDecimal("25.00"), "EUR")))
                .isInstanceOf(InsufficientFundsException.class);

        // Balance unchanged after the failed (rolled-back) transfer.
        assertThat(walletService.getAccount(a.getId()).getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void currencyMismatchIsRejected() {
        Account a = walletService.createAccount("EUR");
        deposit(a, "10.00");

        assertThatThrownBy(() -> walletService.transfer(new TransferRequest(
                null, a.getId(), null, new BigDecimal("1.00"), "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void negativeAmountIsRejected() {
        Account a = walletService.createAccount("EUR");

        assertThatThrownBy(() -> walletService.transfer(new TransferRequest(
                null, null, a.getId(), new BigDecimal("-5.00"), "EUR")))
                .isInstanceOf(InvalidTransferException.class);
    }

    @Test
    void retryWithSameIdempotencyKeyAppliesOnce() {
        Account a = walletService.createAccount("EUR");
        deposit(a, "100.00");
        Account b = walletService.createAccount("EUR");

        TransferRequest request = new TransferRequest(
                "txn-123", a.getId(), b.getId(), new BigDecimal("40.00"), "EUR");

        Transaction first = walletService.transfer(request);
        Transaction second = walletService.transfer(request); // retry

        assertThat(second.getId()).isEqualTo(first.getId());
        // Money moved only once.
        assertThat(walletService.getAccount(a.getId()).getBalance()).isEqualByComparingTo("60.00");
        assertThat(walletService.getAccount(b.getId()).getBalance()).isEqualByComparingTo("40.00");
        // Two transactions exist: the seeding deposit and the (idempotent) transfer.
        // The retry must NOT have created a third.
        assertThat(transactionRepository.count()).isEqualTo(2);
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestIsRejected() {
        Account a = walletService.createAccount("EUR");
        deposit(a, "100.00");
        Account b = walletService.createAccount("EUR");

        walletService.transfer(new TransferRequest(
                "txn-xyz", a.getId(), b.getId(), new BigDecimal("10.00"), "EUR"));

        assertThatThrownBy(() -> walletService.transfer(new TransferRequest(
                "txn-xyz", a.getId(), b.getId(), new BigDecimal("99.00"), "EUR")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private void deposit(Account account, String amount) {
        walletService.transfer(new TransferRequest(
                null, null, account.getId(), new BigDecimal(amount), "EUR"));
    }
}

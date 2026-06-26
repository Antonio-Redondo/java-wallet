package com.wallet;

import com.wallet.dto.TransferRequest;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.model.Account;
import com.wallet.model.LedgerEntry;
import com.wallet.repository.AccountRepository;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the service with many concurrent transfers and asserts the two
 * invariants that "an incorrect balance must never be possible" reduces to:
 *
 * <ol>
 *   <li><b>Conservation</b> - the sum of all balances never changes
 *       (no money is created or destroyed), and</li>
 *   <li><b>Ledger consistency</b> - every account's balance equals the sum of
 *       its ledger entries.</li>
 * </ol>
 *
 * If locking were missing or wrong, lost updates would break conservation.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletServiceConcurrencyTest {

    private static final int ACCOUNTS = 8;
    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 200;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

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
    void concurrentTransfersConserveMoney() throws InterruptedException {
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account account = walletService.createAccount("EUR");
            walletService.transfer(new TransferRequest(
                    null, null, account.getId(), INITIAL_BALANCE, "EUR"));
            accountIds.add(account.getId());
        }

        BigDecimal expectedTotal = INITIAL_BALANCE.multiply(BigDecimal.valueOf(ACCOUNTS));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                        UUID from = accountIds.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                        UUID to = accountIds.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                        if (from.equals(to)) {
                            continue;
                        }
                        BigDecimal amount = new BigDecimal(ThreadLocalRandom.current().nextInt(1, 50));
                        try {
                            walletService.transfer(new TransferRequest(null, from, to, amount, "EUR"));
                        } catch (InsufficientFundsException ignored) {
                            // Expected sometimes; the transfer is rejected and rolled back.
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();                       // release all threads at once
        assertThat(done.await(60, TimeUnit.SECONDS)) // wait for completion
                .as("all worker threads finished").isTrue();
        pool.shutdownNow();

        // Invariant 1: total money is unchanged.
        BigDecimal total = accountIds.stream()
                .map(id -> walletService.getAccount(id).getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).as("total money conserved").isEqualByComparingTo(expectedTotal);

        // Invariant 2: each balance equals the sum of its ledger entries.
        for (UUID id : accountIds) {
            BigDecimal fromLedger = ledgerEntryRepository
                    .findByAccountIdOrderByTimestampAscIdAsc(id).stream()
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(fromLedger)
                    .as("ledger matches balance for account %s", id)
                    .isEqualByComparingTo(walletService.getAccount(id).getBalance());
        }
    }
}

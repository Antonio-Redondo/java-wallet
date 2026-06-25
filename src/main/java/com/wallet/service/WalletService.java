package com.wallet.service;

import com.wallet.dto.TransferRequest;
import com.wallet.exception.AccountNotFoundException;
import com.wallet.exception.CurrencyMismatchException;
import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.InvalidTransferException;
import com.wallet.model.Account;
import com.wallet.model.LedgerEntry;
import com.wallet.model.Transaction;
import com.wallet.model.TransactionType;
import com.wallet.repository.AccountRepository;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core bookkeeping logic.
 *
 * <h2>How correctness and thread safety are guaranteed</h2>
 *
 * Every mutating operation runs inside a single database transaction
 * ({@code @Transactional}). Before an account's balance is changed, its row is
 * loaded with a pessimistic write lock ({@code SELECT ... FOR UPDATE} via
 * {@link AccountRepository#findByIdForUpdate}). The lock is held until the
 * transaction commits, so concurrent transfers touching the same account are
 * effectively serialised - even when the transfers are handled by different
 * nodes in a cluster, because the lock lives in the shared database.
 *
 * <p>When a transfer touches two accounts, both rows are locked in a
 * deterministic order (ascending account id). Acquiring locks in a consistent
 * global order is what prevents deadlock between two transfers that move money
 * in opposite directions between the same pair of accounts.
 *
 * <p>The balance update and the ledger entries are written in the same
 * transaction, so they either all commit or all roll back: an observer can
 * never see a balance that disagrees with the ledger.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public Account createAccount(String currency) {
        Account account = accountRepository.save(new Account(currency));
        log.info("Created account {} ({})", account.getId(), currency);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> listTransactions(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return ledgerEntryRepository.findByAccountIdOrderByTimestampAscIdAsc(accountId);
    }

    /**
     * Apply a transfer, deposit or withdrawal.
     *
     * @return the resulting (or previously applied, if retried) transaction.
     */
    @Transactional
    public Transaction transfer(TransferRequest request) {
        // 1. Idempotency: if this exact request was already applied, return it.
        String key = emptyToNull(request.idempotencyKey());
        if (key != null) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                Transaction tx = verifyIdempotentMatch(existing.get(), request);
                log.info("Idempotent replay for key '{}' -> returning existing transaction {}",
                        key, tx.getId());
                return tx;
            }
        }

        // 2. Validate the request shape.
        validate(request);
        TransactionType type = resolveType(request);
        BigDecimal amount = request.amount();
        String currency = request.currency();
        log.debug("Processing {} request: from={}, to={}, amount={} {}, idempotencyKey={}",
                type, request.fromAccountId(), request.toAccountId(), amount, currency, key);

        Transaction transaction = new Transaction(
                key, type, request.fromAccountId(), request.toAccountId(), amount, currency);

        // 3. Lock the affected accounts (in deterministic order), apply, and record.
        switch (type) {
            case TRANSFER -> {
                Map<UUID, Account> locked =
                        lockAccounts(List.of(request.fromAccountId(), request.toAccountId()));
                Account from = locked.get(request.fromAccountId());
                Account to = locked.get(request.toAccountId());

                requireSameCurrency(from, currency);
                requireSameCurrency(to, currency);

                from.debit(amount);
                to.credit(amount);

                transactionRepository.save(transaction);
                recordEntry(from, transaction, amount.negate());
                recordEntry(to, transaction, amount);
            }
            case DEPOSIT -> {
                Account to = lockAccounts(List.of(request.toAccountId())).get(request.toAccountId());
                requireSameCurrency(to, currency);

                to.credit(amount);

                transactionRepository.save(transaction);
                recordEntry(to, transaction, amount);
            }
            case WITHDRAWAL -> {
                Account from = lockAccounts(List.of(request.fromAccountId())).get(request.fromAccountId());
                requireSameCurrency(from, currency);

                from.debit(amount);

                transactionRepository.save(transaction);
                recordEntry(from, transaction, amount.negate());
            }
        }

        // Account balance changes are flushed automatically: the entities were
        // loaded inside this transaction and are therefore managed by JPA.
        log.info("{} applied: tx={}, from={}, to={}, amount={} {}",
                type, transaction.getId(), request.fromAccountId(), request.toAccountId(),
                amount, currency);
        return transaction;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Load the given accounts, each under a pessimistic write lock, acquiring
     * the locks in ascending id order to avoid deadlocks.
     */
    private Map<UUID, Account> lockAccounts(List<UUID> ids) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        ids.stream().distinct().sorted().forEach(id -> {
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException(id));
            locked.put(id, account);
        });
        log.debug("Acquired write locks on accounts (in order): {}", locked.keySet());
        return locked;
    }

    private void recordEntry(Account account, Transaction transaction, BigDecimal signedAmount) {
        ledgerEntryRepository.save(new LedgerEntry(
                account.getId(), transaction.getId(), signedAmount, account.getBalance()));
    }

    private void requireSameCurrency(Account account, String currency) {
        if (!account.getCurrency().equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException(account.getId(), account.getCurrency(), currency);
        }
    }

    private TransactionType resolveType(TransferRequest r) {
        boolean hasFrom = r.fromAccountId() != null;
        boolean hasTo = r.toAccountId() != null;
        if (hasFrom && hasTo) {
            return TransactionType.TRANSFER;
        }
        return hasTo ? TransactionType.DEPOSIT : TransactionType.WITHDRAWAL;
    }

    private void validate(TransferRequest r) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new InvalidTransferException("amount must be greater than zero");
        }
        if (r.currency() == null || r.currency().isBlank()) {
            throw new InvalidTransferException("currency is required");
        }
        if (r.fromAccountId() == null && r.toAccountId() == null) {
            throw new InvalidTransferException(
                    "at least one of fromAccountId or toAccountId must be provided");
        }
        if (r.fromAccountId() != null && r.fromAccountId().equals(r.toAccountId())) {
            throw new InvalidTransferException("fromAccountId and toAccountId must be different");
        }
    }

    /**
     * Confirm that a retried request with a known idempotency key carries the
     * same parameters as the original. If it does, the original transaction is
     * returned unchanged; if not, the key is being misused and we reject it.
     */
    private Transaction verifyIdempotentMatch(Transaction existing, TransferRequest r) {
        boolean matches =
                Objects.equals(existing.getFromAccountId(), r.fromAccountId())
                        && Objects.equals(existing.getToAccountId(), r.toAccountId())
                        && existing.getAmount().compareTo(r.amount()) == 0
                        && existing.getCurrency().equalsIgnoreCase(r.currency());
        if (!matches) {
            throw new IdempotencyConflictException(r.idempotencyKey());
        }
        return existing;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}

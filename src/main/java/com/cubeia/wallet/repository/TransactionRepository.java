package com.cubeia.wallet.repository;

import com.cubeia.wallet.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /** Used to detect a retried request and return the already-applied result. */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}

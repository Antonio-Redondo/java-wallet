package com.wallet.repository;

import com.wallet.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Load an account while taking a pessimistic write lock on its row
     * ({@code SELECT ... FOR UPDATE}).
     *
     * <p>This is the cornerstone of the concurrency strategy. While the lock is
     * held, no other transaction - on this node or any other node sharing the
     * database - can read-for-update or modify the same account. Combined with
     * the surrounding {@code @Transactional} boundary, this serialises all
     * balance changes to a given account and makes an incorrect balance
     * impossible, even across a cluster of wallet servers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}

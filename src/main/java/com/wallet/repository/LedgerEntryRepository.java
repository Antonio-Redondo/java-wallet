package com.wallet.repository;

import com.wallet.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** All entries that touched an account, oldest first. */
    List<LedgerEntry> findByAccountIdOrderByTimestampAscIdAsc(UUID accountId);
}

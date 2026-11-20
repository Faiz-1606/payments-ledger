package com.example.ledger.ledger;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Newest first; served by the (account_id, id DESC) index. */
    List<LedgerEntry> findTop20ByAccountIdOrderByIdDesc(Long accountId);

    List<LedgerEntry> findByTransferIdOrderByIdAsc(Long transferId);
}

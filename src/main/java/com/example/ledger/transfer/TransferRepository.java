package com.example.ledger.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /** Transfers touching one account, newest first. */
    @Query("""
            select t from Transfer t
            where t.fromAccountId = :accountId or t.toAccountId = :accountId
            order by t.id desc""")
    Page<Transfer> findInvolvingAccount(@Param("accountId") Long accountId, Pageable pageable);

    Page<Transfer> findAllByOrderByIdDesc(Pageable pageable);
}

package com.example.ledger.account;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOwnerIdOrderByIdAsc(Long ownerId);

    Optional<Account> findFirstByKind(AccountKind kind);

    /**
     * {@code PESSIMISTIC_WRITE} makes Hibernate append {@code FOR UPDATE} to the SELECT.
     * PostgreSQL then row-locks the account until the current transaction ends; any other
     * transaction that asks for the same lock waits. Only meaningful inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}

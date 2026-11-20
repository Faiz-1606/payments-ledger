package com.example.ledger.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation at startup. Method names are parsed into
 * queries: {@code existsByEmailIgnoreCase} becomes
 * {@code select count(*) > 0 from users where lower(email) = lower(?)}.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCase(String email);
}

package com.example.ledger.account;

import org.springframework.stereotype.Component;

/**
 * Looks up the system funding account that Flyway created in V1. The id never changes
 * once the database exists, so we cache it after the first lookup. {@code volatile}
 * makes the cached value visible to all request threads.
 */
@Component
public class SystemAccounts {

    private final AccountRepository accountRepository;
    private volatile Long fundingAccountId;

    public SystemAccounts(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public long fundingAccountId() {
        Long cached = fundingAccountId;
        if (cached == null) {
            cached = accountRepository.findFirstByKind(AccountKind.FUNDING)
                    .map(Account::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Funding account missing: the V1 Flyway migration should have created it"));
            fundingAccountId = cached;
        }
        return cached;
    }
}

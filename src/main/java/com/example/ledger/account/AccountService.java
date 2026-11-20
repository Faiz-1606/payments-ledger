package com.example.ledger.account;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ledger.common.error.NotFoundException;
import com.example.ledger.ledger.LedgerEntry;
import com.example.ledger.ledger.LedgerEntryRepository;
import com.example.ledger.security.AccessPolicy;
import com.example.ledger.security.CurrentUser;
import com.example.ledger.user.UserRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;

    public AccountService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          UserRepository userRepository,
                          AccessPolicy accessPolicy) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public Account openAccount(long ownerId) {
        if (!userRepository.existsById(ownerId)) {
            throw NotFoundException.user(ownerId);
        }
        return accountRepository.save(Account.openUserWallet(ownerId));
    }

    /**
     * {@code readOnly = true} lets Hibernate skip dirty checking at the end (nothing will be
     * written) and tells the JDBC driver the transaction is read-only. Both reads below run in
     * the same transaction, so they see one consistent connection.
     */
    @Transactional(readOnly = true)
    public AccountDetails getAccountDetails(CurrentUser caller, long accountId) {
        Account account = getAccount(accountId);
        accessPolicy.requireCanView(caller, account);
        List<LedgerEntry> recent = ledgerEntryRepository.findTop20ByAccountIdOrderByIdDesc(accountId);
        return new AccountDetails(account, recent);
    }

    @Transactional(readOnly = true)
    public Account getAccount(long accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> NotFoundException.account(accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> listAccountsOwnedBy(long ownerId) {
        return accountRepository.findByOwnerIdOrderByIdAsc(ownerId);
    }

    /** An account plus its most recent ledger lines. */
    public record AccountDetails(Account account, List<LedgerEntry> recentEntries) {
    }
}

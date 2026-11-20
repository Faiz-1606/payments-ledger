package com.example.ledger.account;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.ledger.LedgerEntry;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.openAccount(request.ownerId());
        return ResponseEntity.created(URI.create("/api/accounts/" + account.getId()))
                .body(AccountResponse.from(account, List.of()));
    }

    /** Balance plus the 20 most recent ledger entries. */
    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable @Positive long accountId) {
        AccountService.AccountDetails details = accountService.getAccountDetails(accountId);
        return AccountResponse.from(details.account(), details.recentEntries());
    }

    @GetMapping
    public List<AccountResponse> listAccounts(@RequestParam @Positive long ownerId) {
        return accountService.listAccountsOwnedBy(ownerId).stream()
                .map(account -> AccountResponse.from(account, List.of()))
                .toList();
    }

    public record OpenAccountRequest(@NotNull @Positive Long ownerId) {
    }

    public record AccountResponse(Long id, Long ownerId, AccountKind kind, String currency,
                                  long balancePaise, Instant createdAt, List<EntryResponse> recentEntries) {

        static AccountResponse from(Account account, List<LedgerEntry> entries) {
            return new AccountResponse(account.getId(), account.getOwnerId(), account.getKind(),
                    account.getCurrency(), account.getBalancePaise(), account.getCreatedAt(),
                    entries.stream().map(EntryResponse::from).toList());
        }
    }

    public record EntryResponse(Long id, Long transferId, long amountPaise, Instant createdAt) {
        static EntryResponse from(LedgerEntry entry) {
            return new EntryResponse(entry.getId(), entry.getTransferId(), entry.getAmountPaise(),
                    entry.getCreatedAt());
        }
    }
}

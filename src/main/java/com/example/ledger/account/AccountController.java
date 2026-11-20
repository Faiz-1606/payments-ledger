package com.example.ledger.account;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.common.error.ForbiddenException;
import com.example.ledger.ledger.LedgerEntry;
import com.example.ledger.security.CurrentUser;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Opens a new INR wallet owned by the caller. No request body. */
    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(@AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.openAccount(CurrentUser.from(jwt).userId());
        return ResponseEntity.created(URI.create("/api/accounts/" + account.getId()))
                .body(AccountResponse.from(account, List.of()));
    }

    /** Balance plus the 20 most recent ledger entries. Owner or ADMIN only. */
    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long accountId) {
        AccountService.AccountDetails details = accountService.getAccountDetails(CurrentUser.from(jwt), accountId);
        return AccountResponse.from(details.account(), details.recentEntries());
    }

    /** The caller's accounts. An ADMIN may pass {@code ownerId} to list anyone's. */
    @GetMapping
    public List<AccountResponse> listAccounts(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(required = false) @Positive Long ownerId) {
        CurrentUser caller = CurrentUser.from(jwt);
        long owner = ownerId == null ? caller.userId() : ownerId;
        if (owner != caller.userId() && !caller.admin()) {
            throw new ForbiddenException("You can only list your own accounts");
        }
        return accountService.listAccountsOwnedBy(owner).stream()
                .map(account -> AccountResponse.from(account, List.of()))
                .toList();
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

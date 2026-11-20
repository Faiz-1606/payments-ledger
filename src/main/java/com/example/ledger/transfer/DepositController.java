package com.example.ledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.common.Money;
import com.example.ledger.idempotency.IdempotencyKeys;
import com.example.ledger.security.CurrentUser;

/** Deposits move money from the system funding account into a user wallet. ADMIN only. */
@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final TransferService transferService;

    public DepositController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(IdempotencyKeys.HEADER) @Pattern(regexp = IdempotencyKeys.PATTERN) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        TransferOutcome outcome = transferService.deposit(CurrentUser.from(jwt), idempotencyKey,
                request.accountId(), request.amountPaise());
        return TransferController.created(outcome);
    }

    public record DepositRequest(
            @NotNull @Positive Long accountId,
            @NotNull @Positive @Max(Money.MAX_AMOUNT_PAISE) Long amountPaise) {
    }
}

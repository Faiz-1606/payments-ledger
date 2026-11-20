package com.example.ledger.transfer;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.common.Money;
import com.example.ledger.common.PageResponse;
import com.example.ledger.idempotency.IdempotencyKeys;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * The Idempotency-Key header is required. A retry with the same key and body returns the
     * original response (with {@code Idempotent-Replayed: true}) and moves no money.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(IdempotencyKeys.HEADER) @Pattern(regexp = IdempotencyKeys.PATTERN) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferOutcome outcome = transferService.transfer(idempotencyKey,
                request.fromAccountId(), request.toAccountId(), request.amountPaise());
        return created(outcome);
    }

    static ResponseEntity<TransferResponse> created(TransferOutcome outcome) {
        return ResponseEntity.created(URI.create("/api/transfers/" + outcome.response().id()))
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }

    @GetMapping
    public PageResponse<TransferResponse> listTransfers(
            @RequestParam(required = false) @Positive Long accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(transferService.listTransfers(accountId, PageRequest.of(page, size)),
                TransferResponse::from);
    }

    @GetMapping("/{transferId}")
    public TransferResponse getTransfer(@PathVariable @Positive long transferId) {
        return TransferResponse.from(transferService.getTransfer(transferId));
    }

    /**
     * Wrapper types ({@code Long}) plus {@code @NotNull} so that a missing field is a 400,
     * rather than silently becoming 0.
     */
    public record TransferRequest(
            @NotNull @Positive Long fromAccountId,
            @NotNull @Positive Long toAccountId,
            @NotNull @Positive @Max(Money.MAX_AMOUNT_PAISE) Long amountPaise) {
    }
}

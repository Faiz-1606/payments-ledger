package com.example.ledger.transfer;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ledger.common.Money;
import com.example.ledger.common.PageResponse;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amountPaise());
        return ResponseEntity.created(URI.create("/api/transfers/" + response.id())).body(response);
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

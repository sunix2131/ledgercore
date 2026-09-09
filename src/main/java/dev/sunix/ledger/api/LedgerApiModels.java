package dev.sunix.ledger.api;

import dev.sunix.ledger.domain.Account;
import dev.sunix.ledger.domain.EntrySide;
import dev.sunix.ledger.domain.LedgerTransaction;
import dev.sunix.ledger.domain.Posting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LedgerApiModels {
    private LedgerApiModels() {}

    public record CreateAccountRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull EntrySide normalSide,
            boolean allowNegative) {}

    public record CreateTransactionRequest(
            @NotBlank @Size(max = 160) String reference,
            @NotBlank @Size(max = 500) String description,
            @NotEmpty @Size(min = 2, max = 100) List<@NotNull @Valid PostingRequest> postings) {}

    public record PostingRequest(
            @NotNull UUID accountId,
            @NotNull EntrySide side,
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record ReverseTransactionRequest(@NotBlank @Size(max = 500) String reason) {}

    public record TransactionResponse(
            UUID id,
            String reference,
            String description,
            UUID reversalOf,
            String createdBy,
            String requestId,
            Instant createdAt,
            List<Posting> postings,
            boolean idempotentReplay) {
        static TransactionResponse from(LedgerTransaction transaction, boolean replay) {
            return new TransactionResponse(
                    transaction.id(),
                    transaction.reference(),
                    transaction.description(),
                    transaction.reversalOf(),
                    transaction.createdBy(),
                    transaction.requestId(),
                    transaction.createdAt(),
                    transaction.postings(),
                    replay);
        }
    }

    public record BalanceResponse(UUID accountId, String currency, long balanceMinor) {}

    public record PostingPage(List<Posting> items, int limit, int offset) {}

    public record ErrorResponse(String code, String message, Instant timestamp) {}

}

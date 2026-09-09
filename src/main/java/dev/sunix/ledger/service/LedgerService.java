package dev.sunix.ledger.service;

import dev.sunix.ledger.api.LedgerApiModels.CreateTransactionRequest;
import dev.sunix.ledger.api.LedgerApiModels.PostingRequest;
import dev.sunix.ledger.domain.Account;
import dev.sunix.ledger.domain.EntrySide;
import dev.sunix.ledger.domain.LedgerException;
import dev.sunix.ledger.domain.LedgerTransaction;
import dev.sunix.ledger.domain.Posting;
import dev.sunix.ledger.persistence.LedgerRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final LedgerRepository repository;
    private final Clock clock;

    public LedgerService(LedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public WriteResult create(
            CreateTransactionRequest request, String idempotencyKey, String actor, String requestId) {
        String requestHash = hash(request);
        return idempotentWrite(idempotencyKey, requestHash, () -> createNew(request, actor, requestId));
    }

    @Transactional
    public WriteResult reverse(
            UUID transactionId, String reason, String idempotencyKey, String actor, String requestId) {
        String requestHash = hashReversal(transactionId, reason);
        return idempotentWrite(idempotencyKey, requestHash, () -> createReversal(transactionId, reason, actor, requestId));
    }

    public LedgerTransaction get(UUID id) {
        return repository.findTransaction(id).orElseThrow(() -> new LedgerException(
                HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction %s does not exist".formatted(id)));
    }

    private WriteResult idempotentWrite(String key, String requestHash, Supplier<LedgerTransaction> operation) {
        validateIdempotencyKey(key);
        repository.acquireIdempotencyLock(key);

        var existing = repository.findIdempotencyRecord(key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new LedgerException(
                        HttpStatus.CONFLICT,
                        "idempotency_conflict",
                        "The idempotency key was already used with a different request");
            }
            LedgerTransaction transaction = repository.findTransaction(existing.get().transactionId())
                    .orElseThrow(() -> new IllegalStateException("Idempotency record points to a missing transaction"));
            return new WriteResult(transaction, true);
        }

        LedgerTransaction transaction = operation.get();
        repository.insertTransaction(transaction);
        repository.insertIdempotencyRecord(key, requestHash, transaction.id(), transaction.createdAt());
        return new WriteResult(transaction, false);
    }

    private LedgerTransaction createNew(CreateTransactionRequest request, String actor, String requestId) {
        UUID transactionId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        List<Posting> postings = request.postings().stream()
                .map(entry -> posting(transactionId, entry, createdAt))
                .toList();

        validateAndLock(postings);
        return new LedgerTransaction(
                transactionId,
                request.reference().trim(),
                request.description().trim(),
                null,
                normalizedMetadata(actor, "anonymous"),
                normalizedMetadata(requestId, UUID.randomUUID().toString()),
                createdAt,
                postings);
    }

    private LedgerTransaction createReversal(
            UUID transactionId, String reason, String actor, String requestId) {
        LedgerTransaction original = repository.findTransaction(transactionId).orElseThrow(() -> new LedgerException(
                HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction %s does not exist".formatted(transactionId)));
        if (original.reversalOf() != null) {
            throw new LedgerException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "reversal_of_reversal",
                    "A reversal transaction cannot be reversed");
        }

        UUID reversalId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        List<Posting> postings = original.postings().stream()
                .map(entry -> new Posting(
                        UUID.randomUUID(),
                        reversalId,
                        entry.accountId(),
                        entry.side().opposite(),
                        entry.amountMinor(),
                        entry.currency(),
                        createdAt))
                .toList();

        validateAndLock(postings);
        return new LedgerTransaction(
                reversalId,
                "reversal:%s".formatted(original.id()),
                reason.trim(),
                original.id(),
                normalizedMetadata(actor, "anonymous"),
                normalizedMetadata(requestId, UUID.randomUUID().toString()),
                createdAt,
                postings);
    }

    private void validateAndLock(List<Posting> postings) {
        if (postings.size() < 2) {
            throw unprocessable("too_few_postings", "A transaction needs at least two postings");
        }

        Map<String, long[]> totals = new HashMap<>();
        for (Posting posting : postings) {
            if (posting.amountMinor() <= 0) {
                throw unprocessable("invalid_amount", "Posting amounts must be positive minor units");
            }
            long[] currencyTotals = totals.computeIfAbsent(posting.currency(), ignored -> new long[2]);
            int index = posting.side() == EntrySide.DEBIT ? 0 : 1;
            try {
                currencyTotals[index] = Math.addExact(currencyTotals[index], posting.amountMinor());
            } catch (ArithmeticException exception) {
                throw unprocessable("amount_overflow", "Posting totals exceed 64-bit minor units");
            }
        }
        totals.forEach((currency, sides) -> {
            if (sides[0] != sides[1]) {
                throw unprocessable(
                        "unbalanced_transaction",
                        "Debits and credits for %s differ: %d != %d".formatted(currency, sides[0], sides[1]));
            }
        });

        LinkedHashSet<UUID> accountIds = new LinkedHashSet<>();
        postings.stream().map(Posting::accountId).sorted(Comparator.comparing(UUID::toString)).forEach(accountIds::add);
        List<Account> lockedAccounts = repository.lockAccounts(accountIds);
        if (lockedAccounts.size() != accountIds.size()) {
            throw unprocessable("account_not_found", "One or more posting accounts do not exist");
        }

        Map<UUID, Account> accounts = new HashMap<>();
        lockedAccounts.forEach(account -> accounts.put(account.id(), account));
        Map<UUID, Long> deltas = new HashMap<>();
        for (Posting posting : postings) {
            Account account = accounts.get(posting.accountId());
            if (!account.currency().equals(posting.currency())) {
                throw unprocessable(
                        "currency_mismatch",
                        "Posting currency %s does not match account %s currency %s"
                                .formatted(posting.currency(), account.id(), account.currency()));
            }
            long signedAmount = posting.side() == account.normalSide()
                    ? posting.amountMinor()
                    : -posting.amountMinor();
            try {
                deltas.merge(account.id(), signedAmount, Math::addExact);
            } catch (ArithmeticException exception) {
                throw unprocessable("amount_overflow", "Account balance change exceeds 64-bit minor units");
            }
        }

        for (Account account : lockedAccounts) {
            long projected;
            try {
                projected = Math.addExact(repository.balance(account.id()), deltas.getOrDefault(account.id(), 0L));
            } catch (ArithmeticException exception) {
                throw unprocessable("balance_overflow", "Projected account balance exceeds 64-bit minor units");
            }
            if (!account.allowNegative() && projected < 0) {
                throw unprocessable(
                        "insufficient_funds",
                        "Transaction would make account %s negative".formatted(account.id()));
            }
        }
    }

    private Posting posting(UUID transactionId, PostingRequest request, Instant createdAt) {
        return new Posting(
                UUID.randomUUID(),
                transactionId,
                request.accountId(),
                request.side(),
                request.amountMinor(),
                request.currency(),
                createdAt);
    }

    private String hash(CreateTransactionRequest request) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(request.reference());
                output.writeUTF(request.description());
                output.writeInt(request.postings().size());
                for (PostingRequest posting : request.postings()) {
                    output.writeLong(posting.accountId().getMostSignificantBits());
                    output.writeLong(posting.accountId().getLeastSignificantBits());
                    output.writeUTF(posting.side().name());
                    output.writeLong(posting.amountMinor());
                    output.writeUTF(posting.currency());
                }
            }
            return sha256(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }

    private String hashReversal(UUID transactionId, String reason) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF("reverse");
                output.writeLong(transactionId.getMostSignificantBits());
                output.writeLong(transactionId.getLeastSignificantBits());
                output.writeUTF(reason);
            }
            return sha256(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not hash reversal request", exception);
        }
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new LedgerException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_idempotency_key",
                    "Idempotency-Key must contain between 1 and 200 characters");
        }
    }

    private static String normalizedMetadata(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > 160) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "invalid_metadata",
                    "X-Actor and X-Request-Id must not exceed 160 characters");
        }
        return normalized;
    }

    private static LedgerException unprocessable(String code, String message) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public record WriteResult(LedgerTransaction transaction, boolean replay) {}
}

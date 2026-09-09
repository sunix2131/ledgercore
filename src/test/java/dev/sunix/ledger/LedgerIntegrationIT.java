package dev.sunix.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sunix.ledger.api.LedgerApiModels.CreateTransactionRequest;
import dev.sunix.ledger.api.LedgerApiModels.PostingRequest;
import dev.sunix.ledger.domain.Account;
import dev.sunix.ledger.domain.EntrySide;
import dev.sunix.ledger.domain.LedgerException;
import dev.sunix.ledger.service.AccountService;
import dev.sunix.ledger.service.LedgerService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class LedgerIntegrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearLedger() {
        jdbc.sql("TRUNCATE idempotency_record, posting, ledger_transaction, account CASCADE").update();
    }

    @Test
    void duplicateRequestReturnsTheOriginalTransaction() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var request = balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 10_000);

        var first = ledger.create(request, "capital-key", "test", "request-1");
        var replay = ledger.create(request, "capital-key", "test", "request-2");

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(count("ledger_transaction")).isEqualTo(1);
        assertThat(accounts.balance(cash.id())).isEqualTo(10_000);
        assertThat(accounts.balance(equity.id())).isEqualTo(10_000);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIsRejected() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        ledger.create(
                balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 10_000),
                "same-key",
                "test",
                "request-1");

        assertThatThrownBy(() -> ledger.create(
                        balanced("capital-2", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 20_000),
                        "same-key",
                        "test",
                        "request-2"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("different request");
        assertThat(count("ledger_transaction")).isEqualTo(1);
    }

    @Test
    void failedValidationRollsBackWithoutClaimingTheIdempotencyKey() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var unbalanced = new CreateTransactionRequest(
                "bad-entry",
                "does not balance",
                List.of(
                        new PostingRequest(cash.id(), EntrySide.DEBIT, 100, "USD"),
                        new PostingRequest(equity.id(), EntrySide.CREDIT, 99, "USD")));

        assertThatThrownBy(() -> ledger.create(unbalanced, "retryable-key", "test", "request-1"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("differ");
        assertThat(count("ledger_transaction")).isZero();
        assertThat(count("idempotency_record")).isZero();
    }

    @Test
    void postingCurrencyMustMatchItsAccount() {
        Account usd = account("usd", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var request = new CreateTransactionRequest(
                "wrong-currency",
                "currency mismatch",
                List.of(
                        new PostingRequest(usd.id(), EntrySide.DEBIT, 100, "EUR"),
                        new PostingRequest(equity.id(), EntrySide.CREDIT, 100, "EUR")));

        assertThatThrownBy(() -> ledger.create(request, "currency-key", "test", "request-1"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void reversalRestoresBalancesWithoutMutatingTheOriginal() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var original = ledger.create(
                        balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 10_000),
                        "capital-key",
                        "test",
                        "request-1")
                .transaction();

        var reversal = ledger.reverse(original.id(), "entry duplicated", "reverse-key", "operator", "request-2");

        assertThat(reversal.transaction().reversalOf()).isEqualTo(original.id());
        assertThat(accounts.balance(cash.id())).isZero();
        assertThat(accounts.balance(equity.id())).isZero();
        assertThat(count("ledger_transaction")).isEqualTo(2);
        assertThat(ledger.get(original.id()).postings()).hasSize(2);
    }

    @Test
    void accountCannotBeSpentBelowZero() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        Account expense = account("expense", EntrySide.DEBIT, false);
        ledger.create(
                balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100),
                "capital-key",
                "test",
                "request-1");

        assertThatThrownBy(() -> ledger.create(
                        balanced("expense-1", expense, EntrySide.DEBIT, cash, EntrySide.CREDIT, 101),
                        "expense-key",
                        "test",
                        "request-2"))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("negative");
        assertThat(accounts.balance(cash.id())).isEqualTo(100);
    }

    @Test
    void concurrentDuplicateRequestsExecuteOnce() throws Exception {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var request = balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 1_000);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return ledger.create(request, "concurrent-key", "worker-" + index, "request-" + index);
                    }))
                    .toList();
            start.countDown();

            var results = futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            Set<UUID> ids = results.stream().map(result -> result.transaction().id()).collect(java.util.stream.Collectors.toSet());
            assertThat(ids).hasSize(1);
            assertThat(results).filteredOn(result -> !result.replay()).hasSize(1);
            assertThat(count("ledger_transaction")).isEqualTo(1);
        }
    }

    @Test
    void concurrentSpendingCannotOverdrawTheAccount() throws Exception {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        Account expense = account("expense", EntrySide.DEBIT, false);
        ledger.create(
                balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100),
                "capital-key",
                "test",
                "request-1");

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return ledger.create(
                                balanced("expense-" + index, expense, EntrySide.DEBIT, cash, EntrySide.CREDIT, 80),
                                "expense-key-" + index,
                                "worker-" + index,
                                "request-" + index);
                    }))
                    .toList();
            start.countDown();

            int succeeded = 0;
            int rejected = 0;
            for (var future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(LedgerException.class);
                    rejected++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(accounts.balance(cash.id())).isEqualTo(20);
        }
    }

    @Test
    void databaseRejectsPostingMutation() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var transaction = ledger.create(
                        balanced("capital-1", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100),
                        "capital-key",
                        "test",
                        "request-1")
                .transaction();

        assertThatThrownBy(() -> jdbc.sql("UPDATE posting SET amount_minor = 50 WHERE transaction_id = :id")
                        .param("id", transaction.id())
                        .update())
                .hasMessageContaining("immutable");
    }

    @Test
    void databaseRejectsAppendingBalancedPostingsToCommittedHistory() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        UUID id = ledger.create(balanced("sealed", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100),
                "sealed-key", "test", "request").transaction().id();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertPosting(id, cash.id(), "DEBIT", 20, "USD");
            insertPosting(id, equity.id(), "CREDIT", 20, "USD");
        })).hasStackTraceContaining("immutable posting set");
        assertThat(ledger.get(id).postings()).hasSize(2);
        assertThat(accounts.balance(cash.id())).isEqualTo(100);
    }

    @Test
    void databaseRejectsUnbalancedDirectSqlWrites() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertTransactionHeader(id);
            insertPosting(id, cash.id(), "DEBIT", 100, "USD");
            insertPosting(id, equity.id(), "CREDIT", 99, "USD");
        })).hasStackTraceContaining("postings must balance");
        assertThat(count("ledger_transaction")).isZero();
        assertThat(count("posting")).isZero();
    }

    @Test
    void databaseRejectsEmptyTransactionsAndWrongCurrency() {
        assertThatThrownBy(() -> insertTransactionHeader(UUID.randomUUID()))
                .hasStackTraceContaining("posting set");
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertTransactionHeader(id);
            insertPosting(id, cash.id(), "DEBIT", 100, "EUR");
            insertPosting(id, equity.id(), "CREDIT", 100, "EUR");
        })).hasStackTraceContaining("posting currency must match");
        assertThat(count("ledger_transaction")).isZero();
    }

    @Test
    void accountDenominationCannotReinterpretHistoricalPostings() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        assertThatThrownBy(() -> jdbc.sql("UPDATE account SET normal_side = 'CREDIT' WHERE id = :id")
                .param("id", cash.id()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE account SET currency = 'EUR' WHERE id = :id")
                .param("id", cash.id()).update()).hasMessageContaining("immutable");
    }

    @Test
    void maximumLengthReferenceCanBeReversedAndReplayed() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var original = ledger.create(balanced("x".repeat(160), cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100),
                "long-ref", "test", "request").transaction();
        var reverse = ledger.reverse(original.id(), "correction", "reverse", "test", "request");
        var replay = ledger.reverse(original.id(), "correction", "reverse", "test", "request");
        assertThat(reverse.transaction().reference()).hasSizeLessThanOrEqualTo(160);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.transaction().id()).isEqualTo(reverse.transaction().id());
        assertThat(accounts.balance(cash.id())).isZero();
    }

    @Test
    void oversizedAuditMetadataDoesNotClaimAnIdempotencyKey() {
        Account cash = account("cash", EntrySide.DEBIT, false);
        Account equity = account("equity", EntrySide.CREDIT, false);
        var request = balanced("metadata", cash, EntrySide.DEBIT, equity, EntrySide.CREDIT, 100);
        assertThatThrownBy(() -> ledger.create(request, "metadata", "x".repeat(161), "request"))
                .isInstanceOf(LedgerException.class).hasMessageContaining("160");
        assertThat(count("idempotency_record")).isZero();
        assertThat(ledger.create(request, "metadata", "test", "request").replay()).isFalse();
    }

    private void insertTransactionHeader(UUID id) {
        jdbc.sql("""
                INSERT INTO ledger_transaction
                    (id, reference, description, created_by, request_id, created_at, posting_count)
                VALUES (:id, :reference, 'direct SQL test', 'test', 'test', now(), 2)
                """).param("id", id).param("reference", id.toString()).update();
    }

    private void insertPosting(UUID transactionId, UUID accountId, String side, long amount, String currency) {
        jdbc.sql("""
                INSERT INTO posting (id, transaction_id, account_id, side, amount_minor, currency, created_at)
                VALUES (:id, :transactionId, :accountId, CAST(:side AS entry_side), :amount, :currency, now())
                """).param("id", UUID.randomUUID()).param("transactionId", transactionId)
                .param("accountId", accountId).param("side", side).param("amount", amount)
                .param("currency", currency).update();
    }

    private Account account(String name, EntrySide normalSide, boolean allowNegative) {
        return accounts.create(name, "USD", normalSide, allowNegative);
    }

    private static CreateTransactionRequest balanced(
            String reference,
            Account debitAccount,
            EntrySide debitSide,
            Account creditAccount,
            EntrySide creditSide,
            long amountMinor) {
        return new CreateTransactionRequest(
                reference,
                "test transaction",
                List.of(
                        new PostingRequest(debitAccount.id(), debitSide, amountMinor, "USD"),
                        new PostingRequest(creditAccount.id(), creditSide, amountMinor, "USD")));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

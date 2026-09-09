package dev.sunix.ledger.persistence;

import dev.sunix.ledger.domain.Account;
import dev.sunix.ledger.domain.EntrySide;
import dev.sunix.ledger.domain.LedgerTransaction;
import dev.sunix.ledger.domain.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAccount(Account account) {
        jdbc.sql("""
                        INSERT INTO account (id, name, currency, normal_side, allow_negative, created_at)
                        VALUES (:id, :name, :currency, CAST(:normalSide AS entry_side), :allowNegative, :createdAt)
                        """)
                .param("id", account.id())
                .param("name", account.name())
                .param("currency", account.currency())
                .param("normalSide", account.normalSide().name())
                .param("allowNegative", account.allowNegative())
                .param("createdAt", databaseTime(account.createdAt()))
                .update();
    }

    public Optional<Account> findAccount(UUID id) {
        return jdbc.sql("SELECT * FROM account WHERE id = :id")
                .param("id", id)
                .query(LedgerRepository::mapAccount)
                .optional();
    }

    public List<Account> lockAccounts(Collection<UUID> ids) {
        return jdbc.sql("SELECT * FROM account WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(LedgerRepository::mapAccount)
                .list();
    }

    public long balance(UUID accountId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(
                            CASE WHEN p.side = a.normal_side THEN p.amount_minor ELSE -p.amount_minor END
                        ), 0)
                        FROM account a
                        LEFT JOIN posting p ON p.account_id = a.id
                        WHERE a.id = :accountId
                        """)
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    public List<Posting> accountPostings(UUID accountId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT * FROM posting
                        WHERE account_id = :accountId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("accountId", accountId)
                .param("limit", limit)
                .param("offset", offset)
                .query(LedgerRepository::mapPosting)
                .list();
    }

    public void acquireIdempotencyLock(String key) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query(Integer.class)
                .single();
    }

    public Optional<IdempotencyRecord> findIdempotencyRecord(String key) {
        return jdbc.sql("SELECT * FROM idempotency_record WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getObject("transaction_id", UUID.class)))
                .optional();
    }

    public void insertTransaction(LedgerTransaction transaction) {
        jdbc.sql("""
                        INSERT INTO ledger_transaction
                            (id, reference, description, reversal_of, created_by, request_id, created_at, posting_count)
                        VALUES
                            (:id, :reference, :description, :reversalOf, :createdBy, :requestId, :createdAt, :postingCount)
                        """)
                .param("id", transaction.id())
                .param("reference", transaction.reference())
                .param("description", transaction.description())
                .param("reversalOf", transaction.reversalOf(), Types.OTHER)
                .param("createdBy", transaction.createdBy())
                .param("requestId", transaction.requestId())
                .param("createdAt", databaseTime(transaction.createdAt()))
                .param("postingCount", transaction.postings().size())
                .update();

        for (Posting posting : transaction.postings()) {
            jdbc.sql("""
                            INSERT INTO posting
                                (id, transaction_id, account_id, side, amount_minor, currency, created_at)
                            VALUES
                                (:id, :transactionId, :accountId, CAST(:side AS entry_side),
                                 :amountMinor, :currency, :createdAt)
                            """)
                    .param("id", posting.id())
                    .param("transactionId", posting.transactionId())
                    .param("accountId", posting.accountId())
                    .param("side", posting.side().name())
                    .param("amountMinor", posting.amountMinor())
                    .param("currency", posting.currency())
                    .param("createdAt", databaseTime(posting.createdAt()))
                    .update();
        }
    }

    public void insertIdempotencyRecord(String key, String requestHash, UUID transactionId, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO idempotency_record
                            (idempotency_key, request_hash, transaction_id, created_at)
                        VALUES (:key, :requestHash, :transactionId, :createdAt)
                        """)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("transactionId", transactionId)
                .param("createdAt", databaseTime(createdAt))
                .update();
    }

    public Optional<LedgerTransaction> findTransaction(UUID id) {
        return jdbc.sql("SELECT * FROM ledger_transaction WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new LedgerTransaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference"),
                        rs.getString("description"),
                        rs.getObject("reversal_of", UUID.class),
                        rs.getString("created_by"),
                        rs.getString("request_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        List.of()))
                .optional()
                .map(transaction -> new LedgerTransaction(
                        transaction.id(),
                        transaction.reference(),
                        transaction.description(),
                        transaction.reversalOf(),
                        transaction.createdBy(),
                        transaction.requestId(),
                        transaction.createdAt(),
                        postingsForTransaction(transaction.id())));
    }

    private List<Posting> postingsForTransaction(UUID transactionId) {
        return jdbc.sql("SELECT * FROM posting WHERE transaction_id = :transactionId ORDER BY id")
                .param("transactionId", transactionId)
                .query(LedgerRepository::mapPosting)
                .list();
    }

    private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("currency"),
                EntrySide.valueOf(rs.getString("normal_side")),
                rs.getBoolean("allow_negative"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Posting mapPosting(ResultSet rs, int rowNum) throws SQLException {
        return new Posting(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                EntrySide.valueOf(rs.getString("side")),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record IdempotencyRecord(String key, String requestHash, UUID transactionId) {}
}

# LedgerCore

Java and PostgreSQL service for double-entry postings, account balances and reversals. Repeated commands return the original result; competing withdrawals lock the affected accounts before checking available funds.

An independent project with synthetic examples. No employer code or customer data.

## Model

Every transaction has two or more postings. For each currency, total debits must equal total credits. Amounts are signed by the account's normal side and stored as 64-bit minor units; no floating-point money enters the database.

```text
ledger transaction (immutable)
├── debit  cash       USD 10000
└── credit equity     USD 10000
```

An account declares `DEBIT` or `CREDIT` as its normal side. A posting on that side increases its balance; a posting on the opposite side decreases it. Accounts may reject negative projected balances.

Balances are derived from postings. Each transaction records its immutable posting count. Deferred PostgreSQL constraints check the complete posting set at commit: the count must match, each currency must balance, and posting currencies must match their accounts. This also rejects later additions to an existing transaction. Updates and deletes of transactions and postings are rejected; an account's currency and normal side cannot be changed.

## Write path

`POST /api/v1/transactions` requires an `Idempotency-Key`.

1. Acquire a transaction-scoped PostgreSQL advisory lock for the key.
2. Return the existing transaction when the same key and request hash are replayed.
3. Reject the key when it was previously used for a different request.
4. Validate debit/credit totals independently per currency.
5. Lock every affected account in stable UUID order.
6. Validate currency and projected balance, then insert the transaction, postings and idempotency record in one database transaction.

The account locks serialize competing balance checks. If two requests try to spend the last available units concurrently, one observes the other's committed postings and is rejected.

Reversal never edits history. `POST /api/v1/transactions/{id}/reverse` creates a linked transaction with every posting side inverted. The database permits one reversal per original transaction.

## Run

Requirements: Java 21+, Maven 3.9+, Docker with Compose.

```bash
docker compose up -d postgres
mvn spring-boot:run
```

Create two accounts:

```bash
curl -sS localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"cash","currency":"USD","normalSide":"DEBIT","allowNegative":false}'

curl -sS localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"equity","currency":"USD","normalSide":"CREDIT","allowNegative":false}'
```

Use the returned account IDs in a balanced transaction:

```bash
curl -sS localhost:8080/api/v1/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: opening-capital-001' \
  -H 'X-Actor: local-demo' \
  -H 'X-Request-Id: demo-request-001' \
  -d '{
    "reference":"opening-capital-001",
    "description":"initial synthetic balance",
    "postings":[
      {"accountId":"CASH_ACCOUNT_ID","side":"DEBIT","amountMinor":10000,"currency":"USD"},
      {"accountId":"EQUITY_ACCOUNT_ID","side":"CREDIT","amountMinor":10000,"currency":"USD"}
    ]
  }'
```

Read endpoints:

```text
GET /api/v1/accounts/{id}
GET /api/v1/accounts/{id}/balance
GET /api/v1/accounts/{id}/postings?limit=50&offset=0
GET /api/v1/transactions/{id}
```

## Verification

Fast build without Docker:

```bash
mvn test
```

PostgreSQL integration suite:

```bash
mvn verify -DskipITs=false
```

The integration tests use a disposable PostgreSQL container and cover duplicate requests, conflicting retries, rollback, currency mismatch, reversal, insufficient funds, concurrent duplicate delivery, concurrent spending and database-enforced immutability.

## Failure semantics

- Validation errors do not reserve the idempotency key because ledger rows and the key record share one transaction.
- A retry with the same key and byte-equivalent domain request returns the original transaction.
- A retry with changed content returns `409 idempotency_conflict`.
- Unbalanced, wrong-currency and negative-balance writes return `422` domain errors.
- A reversal is an append-only compensating entry; the original remains readable.

## Boundaries

- One service instance may handle concurrent writes; coordination is in PostgreSQL, not in process memory.
- Currency conversion is deliberately outside the ledger. A transaction can contain several currencies only when each currency balances independently.
- Authentication, authorization, chart-of-accounts policy, period closing and financial reporting are not implemented. `X-Actor` is caller-supplied audit metadata, not an authenticated identity. Run behind a trusted application boundary.
- Amounts use signed 64-bit minor units. The service rejects arithmetic overflow; currencies with nonstandard decimal exponents are interpreted by callers.
- History uses offset pagination ordered by `(created_at, id)`; concurrent inserts can shift pages.

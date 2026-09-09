ALTER TABLE ledger_transaction ADD COLUMN posting_count SMALLINT;

-- Flyway runs this migration in a transaction; the table lock covers the backfill.
ALTER TABLE ledger_transaction DISABLE TRIGGER immutable_transaction;
UPDATE ledger_transaction t
SET posting_count = (SELECT COUNT(*) FROM posting p WHERE p.transaction_id = t.id);
ALTER TABLE ledger_transaction ENABLE TRIGGER immutable_transaction;
ALTER TABLE ledger_transaction ALTER COLUMN posting_count SET NOT NULL;
ALTER TABLE ledger_transaction ADD CONSTRAINT valid_posting_count CHECK (posting_count BETWEEN 2 AND 100);

CREATE FUNCTION validate_posting_set() RETURNS trigger AS $$
DECLARE
    target_id UUID;
    expected_count INTEGER;
    actual_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transaction' THEN
        target_id := NEW.id;
    ELSE
        target_id := NEW.transaction_id;
    END IF;

    SELECT posting_count INTO expected_count FROM ledger_transaction WHERE id = target_id;
    SELECT COUNT(*) INTO actual_count FROM posting WHERE transaction_id = target_id;
    IF actual_count <> expected_count THEN
        RAISE EXCEPTION 'immutable posting set must contain exactly % entries', expected_count
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT currency FROM posting WHERE transaction_id = target_id
        GROUP BY currency
        HAVING SUM(CASE WHEN side = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
    ) THEN
        RAISE EXCEPTION 'postings must balance independently for every currency'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM posting p JOIN account a ON a.id = p.account_id
        WHERE p.transaction_id = target_id AND p.currency <> a.currency
    ) THEN
        RAISE EXCEPTION 'posting currency must match its account' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER complete_transaction
    AFTER INSERT ON ledger_transaction DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_posting_set();
CREATE CONSTRAINT TRIGGER complete_posting_set
    AFTER INSERT ON posting DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_posting_set();

CREATE FUNCTION protect_account_denomination() RETURNS trigger AS $$
BEGIN
    IF NEW.currency IS DISTINCT FROM OLD.currency OR NEW.normal_side IS DISTINCT FROM OLD.normal_side THEN
        RAISE EXCEPTION 'account currency and normal side are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER immutable_account_denomination
    BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION protect_account_denomination();

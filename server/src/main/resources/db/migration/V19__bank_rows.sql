-- The inbox, slice 1: bank feeds and the rows they bring in, with what was decided about each.
-- A feed is what a bank provides (one currency pocket, one card); several feeds can belong to one ledger account.
-- A bank row keeps the bank's raw JSON and the fields read out of it. What it became is a link: to an expense
-- (several rows can share one), to an income line, or a skip reason. None of them means it's still in the inbox,
-- so deleting the expense or income it became puts the row back.

CREATE TABLE bank_feeds (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    account_id   uuid        NOT NULL,
    name         text        NOT NULL CHECK (length(trim(name)) > 0),
    -- How the bank's rows are read: card descriptors, transaction codes and dates differ per bank.
    institution  text        NOT NULL CHECK (institution IN ('revolut', 'csob', 'kb', 'raiffeisen', 'other')),
    -- 'file': statements uploaded by hand. 'gocardless' comes with live sync.
    source       text        NOT NULL DEFAULT 'file' CHECK (source IN ('file', 'gocardless')),
    external_id  text,
    iban         text,
    currency     text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Rows booked earlier are history the ledger already has; they aren't brought in.
    import_from  date,
    version      integer     NOT NULL DEFAULT 1,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    UNIQUE (household_id, external_id),
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX bank_feeds_name ON bank_feeds (household_id, lower(name));

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON bank_feeds
    FOR EACH ROW EXECUTE FUNCTION record_audit('bank_feed');

ALTER TABLE income ADD CONSTRAINT income_household_id_id_key UNIQUE (household_id, id);

CREATE TABLE bank_rows (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    feed_id              uuid        NOT NULL,
    -- The bank's id for the transaction, or for a statement without ids a hash of the row ("csv:…").
    external_id          text        NOT NULL,
    booking_date         date        NOT NULL,
    value_date           date,
    -- When it happened: a card payment's own date where the bank gives it, else the booking date.
    date                 date        NOT NULL,
    -- In the feed's currency, minor units; money out is negative.
    amount_minor         bigint      NOT NULL,
    currency             text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    counterparty_name    text        NOT NULL DEFAULT '',
    counterparty_iban    text,
    -- A domestic account number ("19-2000145399/0800") or masked card, when there's no IBAN.
    counterparty_account text        NOT NULL DEFAULT '',
    -- The descriptor that names who was paid; merchant_key is it without numbers, for matching past decisions.
    text                 text        NOT NULL DEFAULT '',
    message              text        NOT NULL DEFAULT '',
    merchant_key         text        NOT NULL DEFAULT '',
    -- The bank's own transaction type (Revolut's CARD_PAYMENT, TOPUP, EXCHANGE…), where it gives one.
    code                 text        NOT NULL DEFAULT '',
    -- What the reader noticed (between own accounts, a top-up, a card repayment), or what the old app decided.
    hint                 text        NOT NULL DEFAULT '',
    raw                  jsonb       NOT NULL,
    expense_id           uuid,
    income_id            uuid,
    skip_reason          text,
    -- The expense or income line was made from this row, so undoing the last of its rows deletes it.
    created_record       boolean     NOT NULL DEFAULT false,
    decided_by           uuid        REFERENCES users (id) ON DELETE SET NULL,
    -- Filed by a rule or the matcher rather than a person (slice 3).
    decided_auto         boolean     NOT NULL DEFAULT false,
    decided_at           timestamptz,
    version              integer     NOT NULL DEFAULT 1,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (feed_id, external_id),
    FOREIGN KEY (household_id, feed_id) REFERENCES bank_feeds (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, expense_id) REFERENCES expenses (household_id, id) ON DELETE SET NULL (expense_id),
    FOREIGN KEY (household_id, income_id) REFERENCES income (household_id, id) ON DELETE SET NULL (income_id),
    CHECK (num_nonnulls(expense_id, income_id, skip_reason) <= 1)
);
CREATE INDEX bank_rows_feed ON bank_rows (household_id, feed_id, date);
CREATE INDEX bank_rows_pending ON bank_rows (household_id, date)
    WHERE expense_id IS NULL AND income_id IS NULL AND skip_reason IS NULL;
CREATE INDEX bank_rows_expense ON bank_rows (expense_id) WHERE expense_id IS NOT NULL;
CREATE INDEX bank_rows_income ON bank_rows (income_id) WHERE income_id IS NOT NULL;
CREATE INDEX bank_rows_merchant_key ON bank_rows (household_id, merchant_key) WHERE expense_id IS NOT NULL;

-- The bank's raw JSON stays out of the log: it never changes and would be copied on every decision.
CREATE OR REPLACE FUNCTION record_audit() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    -- Secrets never go into the log.
    old_row jsonb := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - 'token_hash' - 'raw' END;
    new_row jsonb := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - 'token_hash' - 'raw' END;
    row_data jsonb := coalesce(new_row, old_row);
    household uuid := coalesce(row_data ->> 'household_id', row_data ->> 'id')::uuid;
BEGIN
    -- Bookkeeping-only updates (nothing but updated_at or version moved) aren't changes worth listing.
    IF TG_OP = 'UPDATE' AND (old_row - 'updated_at' - 'version') = (new_row - 'updated_at' - 'version') THEN
        RETURN NULL;
    END IF;
    -- Rows deleted along with their household have nothing left to belong to.
    IF NOT EXISTS (SELECT 1 FROM households WHERE id = household) THEN
        RETURN NULL;
    END IF;
    INSERT INTO audit_log (household_id, actor_user_id, entity, entity_id, action, before, after)
    VALUES (
        household,
        nullif(current_setting('app.actor_id', true), '')::uuid,
        TG_ARGV[0],
        coalesce(row_data ->> 'id', row_data ->> 'user_id')::uuid,
        CASE TG_OP WHEN 'INSERT' THEN 'create' WHEN 'UPDATE' THEN 'update' ELSE 'delete' END,
        old_row,
        new_row
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON bank_rows
    FOR EACH ROW EXECUTE FUNCTION record_audit('bank_row');

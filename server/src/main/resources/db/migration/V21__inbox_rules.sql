-- The inbox, slice 3: rules that map bank rows to an outcome, and the household setting for the matcher's own
-- auto-filing. A rule matches on what a row says (counterparty IBAN, merchant key, text), optionally narrowed to a
-- feed, an account or a direction; its outcome is an expense, an income line, a transfer or a skip. With `auto` it
-- files the row without asking; without, it only fills in the suggestion.

CREATE TABLE inbox_rules (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    name              text        NOT NULL CHECK (length(trim(name)) > 0),
    -- Conditions: every one that is set must hold, and at least one of the first three is.
    counterparty_iban text,
    -- Exactly a bank row's merchant_key (normalised, without numbers).
    merchant_key      text,
    -- Normalised (upper case, no diacritics, letters and digits only); looked for in the row's text, counterparty and message.
    text_contains     text,
    feed_id           uuid,
    account_id        uuid,
    -- 'in' (money in) or 'out'; null for both.
    direction         text        CHECK (direction IN ('in', 'out')),
    -- Outcome and what it fills in.
    outcome           text        NOT NULL CHECK (outcome IN ('expense', 'income', 'transfer', 'skip')),
    merchant          text        NOT NULL DEFAULT '',
    category_id       uuid,
    -- Whose the expense is, or who receives the income; null follows the account.
    person_user_id    uuid,
    income_source     text        NOT NULL DEFAULT '',
    other_account_id  uuid,
    skip_reason       text        NOT NULL DEFAULT '',
    auto              boolean     NOT NULL DEFAULT false,
    priority          integer     NOT NULL DEFAULT 0,
    version           integer     NOT NULL DEFAULT 1,
    created_by        uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    CHECK (counterparty_iban IS NOT NULL OR merchant_key IS NOT NULL OR text_contains IS NOT NULL),
    CHECK (outcome <> 'transfer' OR other_account_id IS NOT NULL),
    CHECK (outcome <> 'skip' OR length(trim(skip_reason)) > 0),
    -- A rule narrowed to a feed or account that goes away would match more than it was written for: it goes too.
    FOREIGN KEY (household_id, feed_id) REFERENCES bank_feeds (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, other_account_id) REFERENCES accounts (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE SET NULL (category_id),
    FOREIGN KEY (household_id, person_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE SET NULL (person_user_id)
);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON inbox_rules
    FOR EACH ROW EXECUTE FUNCTION record_audit('inbox_rule');

-- The rule that filed a row; deleting the rule leaves what it filed in place. The matcher's own decisions have none.
ALTER TABLE bank_rows
    ADD COLUMN rule_id uuid,
    ADD FOREIGN KEY (household_id, rule_id) REFERENCES inbox_rules (household_id, id) ON DELETE SET NULL (rule_id);
CREATE INDEX bank_rows_rule ON bank_rows (rule_id) WHERE rule_id IS NOT NULL;
-- The Decided tab's "Automatic" filter.
CREATE INDEX bank_rows_auto ON bank_rows (household_id, date) WHERE decided_auto;

-- Whether the matcher files what it is sure of (transfer pairs, duplicates of hand-entered expenses) without asking.
ALTER TABLE households ADD COLUMN auto_match boolean NOT NULL DEFAULT false;

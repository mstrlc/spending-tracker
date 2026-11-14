-- Debts between members and the split ratios behind them.
--
-- A split rule says how members share spending: the household default, a category's override, or one expense's
-- override. Household and category rules hold from valid_from until the next rule of the same scope; an expense's
-- rule has no date. Its shares are weights: 60 and 40, or 1 and 1; a member without a share (or weight 0) has none.
--
-- Debts aren't stored: they follow from expenses whose account (who paid) doesn't match whose expense it is, and
-- the server works them out when asked. Settlements are what members pay each other back; they're neither income
-- nor spending, and they aren't matched to particular expenses, only added to the running balance.

-- For the composite reference from an expense's own split.
ALTER TABLE expenses ADD CONSTRAINT expenses_household_id_id_key UNIQUE (household_id, id);

CREATE TABLE split_rules (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    category_id  uuid,
    expense_id   uuid,
    valid_from   date,
    version      integer     NOT NULL DEFAULT 1,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    -- An expense's rule has neither a category nor a date; the others always have a date.
    CHECK (CASE WHEN expense_id IS NULL THEN valid_from IS NOT NULL ELSE category_id IS NULL AND valid_from IS NULL END),
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, expense_id) REFERENCES expenses (household_id, id) ON DELETE CASCADE
);
-- One rule per scope and date; one per expense.
CREATE UNIQUE INDEX split_rules_scope ON split_rules (household_id, category_id, valid_from) NULLS NOT DISTINCT
    WHERE expense_id IS NULL;
CREATE UNIQUE INDEX split_rules_expense ON split_rules (expense_id) WHERE expense_id IS NOT NULL;

CREATE TABLE split_shares (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid    NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    rule_id      uuid    NOT NULL,
    user_id      uuid    NOT NULL,
    weight       integer NOT NULL CHECK (weight > 0),
    UNIQUE (rule_id, user_id),
    FOREIGN KEY (household_id, rule_id) REFERENCES split_rules (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

-- Off: the mismatch between who paid and whose it is creates no debt (a gift, or settled on the spot).
ALTER TABLE expenses ADD COLUMN creates_debt boolean NOT NULL DEFAULT true;

CREATE TABLE settlements (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    date         date        NOT NULL,
    from_user_id uuid        NOT NULL,
    to_user_id   uuid        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    notes        text        NOT NULL DEFAULT '',
    version      integer     NOT NULL DEFAULT 1,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    CHECK (from_user_id <> to_user_id),
    -- A member's debts go when they leave (their expenses become joint), and so do their settlements.
    FOREIGN KEY (household_id, from_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    FOREIGN KEY (household_id, to_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX settlements_household_date ON settlements (household_id, date DESC);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON split_rules
    FOR EACH ROW EXECUTE FUNCTION record_audit('split_rule');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON split_shares
    FOR EACH ROW EXECUTE FUNCTION record_audit('split_share');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON settlements
    FOR EACH ROW EXECUTE FUNCTION record_audit('settlement');

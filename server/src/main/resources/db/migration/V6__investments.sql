-- Market holdings (funds, stocks, …): what was put in and when, and what the holding was worth on given days,
-- so the app can show growth. The start of net worth; other assets (property, cars, loans) come later.

CREATE TABLE investments (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    name          text        NOT NULL,
    kind          text        NOT NULL DEFAULT 'fund' CHECK (kind IN ('fund', 'stock', 'bond', 'crypto', 'other')),
    -- NULL: held jointly rather than by one member.
    owner_user_id uuid,
    notes         text        NOT NULL DEFAULT '',
    version       integer     NOT NULL DEFAULT 1,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    FOREIGN KEY (household_id, owner_user_id) REFERENCES household_members (household_id, user_id)
        ON DELETE SET NULL (owner_user_id)
);
CREATE INDEX investments_household ON investments (household_id);

-- Money put into a holding (or taken out of it, as a negative amount), at cost in the household currency.
CREATE TABLE investment_contributions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    investment_id uuid        NOT NULL,
    date          date        NOT NULL,
    amount_minor  bigint      NOT NULL CHECK (amount_minor <> 0),
    -- Where the money came from (or went back to).
    account_id    uuid,
    notes         text        NOT NULL DEFAULT '',
    version       integer     NOT NULL DEFAULT 1,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (household_id, investment_id) REFERENCES investments (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT
);
CREATE INDEX investment_contributions_investment ON investment_contributions (investment_id, date DESC);
CREATE INDEX investment_contributions_household_date ON investment_contributions (household_id, date DESC);

-- What a holding was worth at the end of a day, in the household currency. One per holding and day.
CREATE TABLE investment_valuations (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    investment_id uuid        NOT NULL,
    date          date        NOT NULL,
    value_minor   bigint      NOT NULL CHECK (value_minor >= 0),
    notes         text        NOT NULL DEFAULT '',
    version       integer     NOT NULL DEFAULT 1,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (investment_id, date),
    FOREIGN KEY (household_id, investment_id) REFERENCES investments (household_id, id) ON DELETE CASCADE
);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON investments
    FOR EACH ROW EXECUTE FUNCTION record_audit('investment');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON investment_contributions
    FOR EACH ROW EXECUTE FUNCTION record_audit('investment_contribution');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON investment_valuations
    FOR EACH ROW EXECUTE FUNCTION record_audit('investment_valuation');

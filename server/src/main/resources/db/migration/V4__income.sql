-- Money coming into the household: salaries, transfers in, refunds of tax and the like.
-- Kept apart from expenses (rather than as negative expenses) because income has a payer, not a merchant,
-- lands with a member rather than being paid by one, and never has a spending category.

CREATE TABLE income (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    date              date        NOT NULL,
    -- Minor units of the household currency. Negative for clawbacks (an overpaid salary paid back).
    amount_minor      bigint      NOT NULL CHECK (amount_minor <> 0),
    -- Who paid: an employer, a client, a member topping up the joint account.
    source            text        NOT NULL DEFAULT '',
    account_id        uuid,
    -- NULL: shared income rather than one member's.
    recipient_user_id uuid,
    notes             text        NOT NULL DEFAULT '',
    -- Bank-side transaction id from imports, so re-importing can't duplicate rows.
    external_ref      text,
    version           integer     NOT NULL DEFAULT 1,
    created_by        uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, external_ref),
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (household_id, recipient_user_id) REFERENCES household_members (household_id, user_id)
        ON DELETE SET NULL (recipient_user_id)
);
CREATE INDEX income_household_date ON income (household_id, date DESC);

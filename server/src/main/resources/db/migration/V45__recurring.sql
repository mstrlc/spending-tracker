-- Recurring payments: a merchant charged on a schedule, confirmed from the app's suggestions or added by hand.
-- Due dates are anchor_date plus whole multiples of interval_months (the day clamped to shorter months). Charges are
-- the merchant's expenses near a due date; the API compares them with expected_amount_minor and flags a changed
-- amount or a missed charge. A dismissed row only stops the merchant being suggested again; an ended one is a
-- payment that stopped (a cancelled subscription), kept for the record.

CREATE TABLE recurring_payments (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    merchant_id           uuid        NOT NULL,
    status                text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended', 'dismissed')),
    expected_amount_minor bigint      NOT NULL CHECK (expected_amount_minor >= 0),
    interval_months       integer     NOT NULL DEFAULT 1 CHECK (interval_months IN (1, 2, 3, 6, 12)),
    anchor_date           date        NOT NULL,
    category_id           uuid,
    account_id            uuid,
    paid_by_user_id       uuid,
    notes                 text        NOT NULL DEFAULT '',
    version               integer     NOT NULL DEFAULT 1,
    created_by            uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    -- One per merchant: a merge moves it to the merchant merged into, unless that one has its own.
    UNIQUE (household_id, merchant_id),
    FOREIGN KEY (household_id, merchant_id) REFERENCES merchants (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE SET NULL (category_id),
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE SET NULL (account_id),
    FOREIGN KEY (household_id, paid_by_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE SET NULL (paid_by_user_id)
);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON recurring_payments
    FOR EACH ROW EXECUTE FUNCTION record_audit('recurring_payment');

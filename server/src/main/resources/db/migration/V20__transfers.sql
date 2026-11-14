-- The inbox, slice 2: money moving between the household's own accounts, and what a bank row can become besides
-- an expense or income line. A transfer is made from one or two bank rows (its two sides, when both accounts are
-- read) and is neither income nor spending. A row between two members can instead become a settlement (V16).

CREATE TABLE transfers (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    date            date        NOT NULL,
    -- Household currency, positive: what left the from-account (converted at the ČNB fixing if foreign).
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    from_account_id uuid        NOT NULL,
    to_account_id   uuid        NOT NULL,
    notes           text        NOT NULL DEFAULT '',
    version         integer     NOT NULL DEFAULT 1,
    created_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    -- from = to is allowed: a currency exchange between two pockets of one account is a transfer with both rows.
    FOREIGN KEY (household_id, from_account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (household_id, to_account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT
);
CREATE INDEX transfers_household_date ON transfers (household_id, date DESC);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION record_audit('transfer');

ALTER TABLE bank_rows
    ADD COLUMN transfer_id uuid,
    ADD COLUMN settlement_id uuid,
    ADD FOREIGN KEY (household_id, transfer_id) REFERENCES transfers (household_id, id) ON DELETE SET NULL (transfer_id),
    ADD FOREIGN KEY (household_id, settlement_id) REFERENCES settlements (household_id, id) ON DELETE SET NULL (settlement_id);

ALTER TABLE bank_rows DROP CONSTRAINT bank_rows_check;
ALTER TABLE bank_rows ADD CONSTRAINT bank_rows_check
    CHECK (num_nonnulls(expense_id, income_id, transfer_id, settlement_id, skip_reason) <= 1);

CREATE INDEX bank_rows_transfer ON bank_rows (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX bank_rows_settlement ON bank_rows (settlement_id) WHERE settlement_id IS NOT NULL;

-- The inbox is the rows nothing was decided about.
DROP INDEX bank_rows_pending;
CREATE INDEX bank_rows_pending ON bank_rows (household_id, date)
    WHERE expense_id IS NULL AND income_id IS NULL AND transfer_id IS NULL AND settlement_id IS NULL AND skip_reason IS NULL;

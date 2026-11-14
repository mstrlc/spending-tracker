-- Balance checks: what an account held at the end of a day, entered by hand for an account the bank doesn't report
-- (a savings account the connector can't read, cash). Its balance on other days is worked out from the nearest
-- check and what the ledger recorded on the account in between: transfers, income, expenses and asset contributions.
CREATE TABLE account_balances (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL,
    account_id   uuid        NOT NULL,
    date         date        NOT NULL,
    -- In the household's currency; negative when the account is overdrawn or owes.
    amount_minor bigint      NOT NULL,
    notes        text        NOT NULL DEFAULT '',
    version      integer     NOT NULL DEFAULT 1,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    UNIQUE (account_id, date),
    FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE CASCADE
);
CREATE INDEX account_balances_household ON account_balances (household_id, account_id, date);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON account_balances
    FOR EACH ROW EXECUTE FUNCTION record_audit('account_balance');

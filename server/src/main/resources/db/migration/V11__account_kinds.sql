-- What an account is: a bank account, a credit card (its purchases are spending, paying it off is a transfer,
-- and what's owed on it is a liability), cash, or something else. A bank account or card can carry its IBAN,
-- which bank feeds and transfers match on.

ALTER TABLE accounts ADD COLUMN kind text NOT NULL DEFAULT 'bank'
    CHECK (kind IN ('bank', 'credit_card', 'cash', 'other'));
-- Normalized: no spaces, upper case.
ALTER TABLE accounts ADD COLUMN iban text CHECK (iban ~ '^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$');
CREATE UNIQUE INDEX accounts_iban ON accounts (household_id, iban);

-- Inbox slice 5: refunds attached to the expense they reverse, and the deduction outcome of inbox rules.

-- A refund is a negative expense that points at its original (a positive expense that isn't itself a refund; the API
-- checks that). Reports count it in the original's month and category. Deleting the original leaves the refund as an
-- ordinary negative expense on its own date.
ALTER TABLE expenses
    ADD COLUMN refund_of uuid,
    ADD FOREIGN KEY (household_id, refund_of) REFERENCES expenses (household_id, id) ON DELETE SET NULL (refund_of);
CREATE INDEX expenses_refund_of ON expenses (refund_of) WHERE refund_of IS NOT NULL;

-- Rules that turn money leaving an account (the health and social insurance advances) into an income deduction:
-- the source and whose it is reuse income_source and person_user_id; the deduction is earned in the row's month plus
-- this many months (the previous month by default).
ALTER TABLE inbox_rules DROP CONSTRAINT inbox_rules_outcome_check;
ALTER TABLE inbox_rules
    ADD CONSTRAINT inbox_rules_outcome_check CHECK (outcome IN ('expense', 'income', 'deduction', 'transfer', 'skip')),
    ADD COLUMN month_offset integer NOT NULL DEFAULT -1 CHECK (month_offset BETWEEN -12 AND 12);

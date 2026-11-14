-- What an expense cost in the currency it was paid in (EUR on a trip, say), next to the household-currency amount
-- everything adds up in. Both or neither.

ALTER TABLE expenses ADD COLUMN original_amount_minor bigint CHECK (original_amount_minor <> 0);
ALTER TABLE expenses ADD COLUMN original_currency char(3) CHECK (original_currency ~ '^[A-Z]{3}$');
ALTER TABLE expenses ADD CONSTRAINT expenses_original_complete
    CHECK ((original_amount_minor IS NULL) = (original_currency IS NULL));

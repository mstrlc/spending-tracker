-- What a contribution cost in the security's or payment currency (EUR, USD…), next to the household-currency
-- amount everything adds up in. Both or neither.

ALTER TABLE asset_contributions ADD COLUMN original_amount_minor bigint CHECK (original_amount_minor <> 0);
ALTER TABLE asset_contributions ADD COLUMN original_currency text CHECK (original_currency ~ '^[A-Z]{3}$');
ALTER TABLE asset_contributions ADD CONSTRAINT asset_contributions_original_complete
    CHECK ((original_amount_minor IS NULL) = (original_currency IS NULL));

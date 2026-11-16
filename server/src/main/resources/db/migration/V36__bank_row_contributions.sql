-- A bank row can become a contribution to an asset (money put into Portu, a withdrawal from a savings scheme), and an
-- inbox rule can say so. Deleting the contribution puts the row back in the inbox; putting the row back deletes a
-- contribution made from it.

ALTER TABLE asset_contributions ADD CONSTRAINT asset_contributions_household_id_id_key UNIQUE (household_id, id);

ALTER TABLE bank_rows ADD COLUMN contribution_id uuid;
ALTER TABLE bank_rows ADD FOREIGN KEY (household_id, contribution_id)
    REFERENCES asset_contributions (household_id, id) ON DELETE SET NULL (contribution_id);
CREATE INDEX bank_rows_contribution ON bank_rows (contribution_id) WHERE contribution_id IS NOT NULL;

ALTER TABLE bank_rows DROP CONSTRAINT bank_rows_check;
ALTER TABLE bank_rows ADD CONSTRAINT bank_rows_check
    CHECK (num_nonnulls(expense_id, income_id, transfer_id, settlement_id, contribution_id, skip_reason) <= 1);

DROP INDEX bank_rows_pending;
CREATE INDEX bank_rows_pending ON bank_rows (household_id, date)
    WHERE expense_id IS NULL AND income_id IS NULL AND transfer_id IS NULL AND settlement_id IS NULL
        AND contribution_id IS NULL AND skip_reason IS NULL;

ALTER TABLE inbox_rules DROP CONSTRAINT inbox_rules_outcome_check;
ALTER TABLE inbox_rules
    ADD CONSTRAINT inbox_rules_outcome_check
        CHECK (outcome IN ('expense', 'income', 'deduction', 'transfer', 'settlement', 'contribution', 'skip')),
    ADD COLUMN asset_id uuid,
    ADD CONSTRAINT inbox_rules_contribution_asset_check CHECK (outcome <> 'contribution' OR asset_id IS NOT NULL),
    ADD FOREIGN KEY (household_id, asset_id) REFERENCES assets (household_id, id) ON DELETE CASCADE;

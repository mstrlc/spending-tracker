-- Settlement outcome for inbox rules: files regular repayments between two members.
ALTER TABLE inbox_rules DROP CONSTRAINT inbox_rules_outcome_check;
ALTER TABLE inbox_rules
    ADD CONSTRAINT inbox_rules_outcome_check
        CHECK (outcome IN ('expense', 'income', 'deduction', 'transfer', 'settlement', 'skip')),
    ADD COLUMN settle_from_user_id uuid,
    ADD COLUMN settle_to_user_id uuid,
    ADD CONSTRAINT inbox_rules_settlement_members_check
        CHECK (outcome <> 'settlement' OR (
            settle_from_user_id IS NOT NULL AND
            settle_to_user_id IS NOT NULL AND
            settle_from_user_id <> settle_to_user_id
        )),
    ADD FOREIGN KEY (household_id, settle_from_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    ADD FOREIGN KEY (household_id, settle_to_user_id) REFERENCES household_members (household_id, user_id)
        ON UPDATE CASCADE ON DELETE CASCADE;

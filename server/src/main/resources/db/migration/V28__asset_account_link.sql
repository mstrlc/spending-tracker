-- An asset or liability can be tracked by an account; its value then comes from that account's feed balances.

ALTER TABLE assets ADD COLUMN account_id uuid;
ALTER TABLE assets ADD FOREIGN KEY (household_id, account_id)
    REFERENCES accounts (household_id, id) ON DELETE SET NULL (account_id);
CREATE UNIQUE INDEX assets_account ON assets (household_id, account_id) WHERE account_id IS NOT NULL;

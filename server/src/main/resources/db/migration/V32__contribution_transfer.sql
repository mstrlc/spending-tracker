-- Link a liability contribution to the transfer that created it; deleting the transfer removes the contribution.

ALTER TABLE asset_contributions ADD COLUMN transfer_id uuid;
ALTER TABLE asset_contributions ADD FOREIGN KEY (household_id, transfer_id)
    REFERENCES transfers (household_id, id) ON DELETE CASCADE;
CREATE UNIQUE INDEX asset_contributions_transfer ON asset_contributions (transfer_id) WHERE transfer_id IS NOT NULL;

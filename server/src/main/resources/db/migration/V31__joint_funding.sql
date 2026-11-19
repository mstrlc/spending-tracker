ALTER TABLE households
    ADD COLUMN joint_funding TEXT NOT NULL DEFAULT 'pot'
    CHECK (joint_funding IN ('pot', 'contributions'));

-- Members without a login: a person in the household (a partner who hasn't signed up, a child) that expenses,
-- income, accounts and assets can belong to. They're users with no email and no password, so they can't sign in.
-- An invite can name one to claim: whoever accepts it takes that member's place, and everything that belonged to
-- the member follows, because every reference to a member cascades when its user id changes.

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
-- Either a full login or none.
ALTER TABLE users ADD CONSTRAINT users_login_complete CHECK ((email IS NULL) = (password_hash IS NULL));

-- References to a member follow it when an invite hands the member over to a real user.
-- Convention for new tables: reference household_members (household_id, user_id) with ON UPDATE CASCADE.
ALTER TABLE accounts DROP CONSTRAINT accounts_household_id_owner_user_id_fkey;
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_fkey FOREIGN KEY (household_id, owner_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE SET NULL (owner_user_id);

ALTER TABLE expenses DROP CONSTRAINT expenses_household_id_paid_by_user_id_fkey;
ALTER TABLE expenses ADD CONSTRAINT expenses_paid_by_fkey FOREIGN KEY (household_id, paid_by_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE SET NULL (paid_by_user_id);

ALTER TABLE income DROP CONSTRAINT income_household_id_recipient_user_id_fkey;
ALTER TABLE income ADD CONSTRAINT income_recipient_fkey FOREIGN KEY (household_id, recipient_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE SET NULL (recipient_user_id);

ALTER TABLE assets DROP CONSTRAINT investments_household_id_owner_user_id_fkey;
ALTER TABLE assets ADD CONSTRAINT assets_owner_fkey FOREIGN KEY (household_id, owner_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE SET NULL (owner_user_id);

-- The member without login an invite hands over, if any. Gone with the member.
ALTER TABLE household_invites ADD COLUMN claim_user_id uuid;
ALTER TABLE household_invites ADD CONSTRAINT household_invites_claim_fkey FOREIGN KEY (household_id, claim_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE CASCADE;

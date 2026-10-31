-- Invitations to join a household. The invite link carries a random token; only its SHA-256 is stored.
-- An invite is bound to one email address and can be accepted once, before it expires.

CREATE TABLE household_invites (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    token_hash   bytea       NOT NULL UNIQUE,
    email        citext      NOT NULL,
    role         text        NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
    invited_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    accepted_at  timestamptz,
    accepted_by  uuid        REFERENCES users (id) ON DELETE SET NULL
);
-- At most one open invite per address and household; re-inviting replaces it.
CREATE UNIQUE INDEX household_invites_open_email ON household_invites (household_id, email) WHERE accepted_at IS NULL;

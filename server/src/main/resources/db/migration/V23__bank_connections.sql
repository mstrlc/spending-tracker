-- Inbox slice 4: live GoCardless. A connection is one consent given at a bank (a requisition); the bank's accounts
-- become feeds (source 'gocardless', external_id = the bank's account id) that a daily sync and "Sync now" read.

CREATE TABLE bank_connections (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id     uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    institution_id   text        NOT NULL,
    institution_name text        NOT NULL,
    requisition_id   text        NOT NULL UNIQUE,
    agreement_id     text,
    -- pending: sent to the bank, not back yet. active: linked, syncing. expired: the consent ran out. revoked: the
    -- bank or the person withdrew it. failed: the bank refused or the link never finished.
    status           text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'expired', 'revoked', 'failed')),
    consent_expires_at timestamptz,
    -- The bank's accounts as the requisition listed them (id, IBAN or masked card, currency, name, owner), read once
    -- when the connection is finished: the details have a daily limit of their own.
    accounts         jsonb       NOT NULL DEFAULT '[]',
    last_error       text        NOT NULL DEFAULT '',
    version          integer     NOT NULL DEFAULT 1,
    created_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id)
);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON bank_connections
    FOR EACH ROW EXECUTE FUNCTION record_audit('bank_connection');

-- Deleting a connection leaves its feeds and their rows; they just stop syncing.
ALTER TABLE bank_feeds
    ADD COLUMN connection_id uuid,
    ADD COLUMN last_synced_at timestamptz,
    ADD FOREIGN KEY (household_id, connection_id) REFERENCES bank_connections (household_id, id) ON DELETE SET NULL (connection_id);
CREATE INDEX bank_feeds_connection ON bank_feeds (connection_id) WHERE connection_id IS NOT NULL;

-- What the bank said the balance was when a sync read it. No audit trigger: written by the sync, never edited, one
-- row per feed per sync, and it would only echo into the Activity log.
CREATE TABLE bank_balances (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    feed_id        uuid        NOT NULL,
    read_at        timestamptz NOT NULL DEFAULT now(),
    -- The date the bank gives the balance for, when it does.
    balance_date   date,
    -- Minor units of [currency], the bank's sign (a card's debt is negative).
    amount_minor   bigint      NOT NULL,
    currency       text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_type   text        NOT NULL DEFAULT '',
    FOREIGN KEY (household_id, feed_id) REFERENCES bank_feeds (household_id, id) ON DELETE CASCADE
);
CREATE INDEX bank_balances_feed ON bank_balances (feed_id, read_at DESC);

-- One row per time a sync asked the bank for a feed's data: GoCardless allows about 4 a day per account, so the
-- sync counts today's rows and refuses more. A log, not household data: no audit trigger.
CREATE TABLE bank_syncs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    feed_id       uuid        NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    -- 'manual' (Sync now) or 'scheduled' (the daily run).
    trigger       text        NOT NULL CHECK (trigger IN ('manual', 'scheduled')),
    added         integer     NOT NULL DEFAULT 0,
    error         text        NOT NULL DEFAULT '',
    FOREIGN KEY (household_id, feed_id) REFERENCES bank_feeds (household_id, id) ON DELETE CASCADE
);
CREATE INDEX bank_syncs_feed ON bank_syncs (feed_id, started_at DESC);

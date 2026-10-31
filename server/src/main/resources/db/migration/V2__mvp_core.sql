-- Users, sessions, households (tenants), and the first domain tables: accounts, categories, expenses.
-- Every domain row carries household_id; every editable row carries version for optimistic concurrency.
-- References between domain rows are composite (household_id, id) foreign keys, so a row can never
-- point at another household's data even if application code forgets to check.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         citext      NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    display_name  text        NOT NULL CHECK (length(trim(display_name)) > 0),
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- The cookie holds a random token; only its SHA-256 is stored, so a database leak can't be replayed.
CREATE TABLE sessions (
    token_hash   bytea PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE households (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL CHECK (length(trim(name)) > 0),
    currency   char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE household_members (
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         text        NOT NULL CHECK (role IN ('owner', 'member')),
    joined_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, user_id)
);
CREATE INDEX household_members_user ON household_members (user_id);

CREATE TABLE accounts (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    name          text        NOT NULL CHECK (length(trim(name)) > 0),
    -- NULL owner: a shared (joint) account.
    owner_user_id uuid,
    status        text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed')),
    version       integer     NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, name),
    UNIQUE (household_id, id),
    FOREIGN KEY (household_id, owner_user_id) REFERENCES household_members (household_id, user_id)
        ON DELETE SET NULL (owner_user_id)
);

-- Two-level tree. kind lives on top-level categories; children inherit it.
-- 'investment' rows are capital moved into investments, counted as invested rather than spent.
CREATE TABLE categories (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    parent_id    uuid,
    name         text        NOT NULL CHECK (length(trim(name)) > 0),
    kind         text        NOT NULL DEFAULT 'consumption' CHECK (kind IN ('consumption', 'investment')),
    archived     boolean     NOT NULL DEFAULT false,
    sort_order   integer     NOT NULL DEFAULT 0,
    version      integer     NOT NULL DEFAULT 1,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    FOREIGN KEY (household_id, parent_id) REFERENCES categories (household_id, id) ON DELETE RESTRICT
);
-- NULLS NOT DISTINCT so two top-level categories can't share a name either.
CREATE UNIQUE INDEX categories_sibling_name ON categories (household_id, parent_id, name) NULLS NOT DISTINCT;

CREATE TABLE expenses (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    date            date        NOT NULL,
    -- Minor units of the household currency. Negative for refunds and clawbacks.
    amount_minor    bigint      NOT NULL CHECK (amount_minor <> 0),
    merchant        text        NOT NULL DEFAULT '',
    category_id     uuid,
    account_id      uuid,
    -- NULL: a shared expense rather than one member's.
    paid_by_user_id uuid,
    notes           text        NOT NULL DEFAULT '',
    -- Bank-side transaction id from imports, so re-importing can't duplicate rows.
    external_ref    text,
    version         integer     NOT NULL DEFAULT 1,
    created_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, external_ref),
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (household_id, account_id) REFERENCES accounts (household_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (household_id, paid_by_user_id) REFERENCES household_members (household_id, user_id)
        ON DELETE SET NULL (paid_by_user_id)
);
CREATE INDEX expenses_household_date ON expenses (household_id, date DESC);

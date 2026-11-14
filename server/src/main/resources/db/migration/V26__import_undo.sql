-- Track each import batch so it can be listed and undone as a whole.

CREATE TABLE imports (
    id           uuid        PRIMARY KEY,
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    counts       jsonb       NOT NULL,
    undone_at    timestamptz,
    undone_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    UNIQUE (household_id, id)
);
CREATE INDEX imports_household ON imports (household_id, created_at DESC);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON imports
    FOR EACH ROW EXECUTE FUNCTION record_audit('import');

-- Deferrable so the importer can insert the imports row at the end with its final counts.
ALTER TABLE household_members
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX household_members_import ON household_members (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE accounts
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX accounts_import ON accounts (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE categories
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX categories_import ON categories (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE trips
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX trips_import ON trips (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE merchants
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX merchants_import ON merchants (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE expenses
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX expenses_import ON expenses (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE income
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX income_import ON income (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE assets
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX assets_import ON assets (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE asset_contributions
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX asset_contributions_import ON asset_contributions (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE bank_feeds
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX bank_feeds_import ON bank_feeds (household_id, import_id) WHERE import_id IS NOT NULL;

ALTER TABLE bank_rows
    ADD COLUMN import_id uuid,
    ADD FOREIGN KEY (household_id, import_id) REFERENCES imports (household_id, id) ON DELETE SET NULL (import_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX bank_rows_import ON bank_rows (household_id, import_id) WHERE import_id IS NOT NULL;

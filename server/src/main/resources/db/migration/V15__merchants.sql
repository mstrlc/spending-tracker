-- Merchants become records: a name (unique per household, ignoring case) and a default category, so top merchants
-- and subscriptions don't split on spelling and renaming one renames it on every expense. Expenses point at one
-- instead of carrying the name as text. (The bank texts that map to a merchant come with the rules table.)

CREATE TABLE merchants (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    name                text        NOT NULL CHECK (length(trim(name)) > 0),
    default_category_id uuid,
    version             integer     NOT NULL DEFAULT 1,
    created_by          uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    -- A deleted category only clears the default; the merchant stays.
    FOREIGN KEY (household_id, default_category_id) REFERENCES categories (household_id, id)
        ON DELETE SET NULL (default_category_id)
);
CREATE UNIQUE INDEX merchants_name ON merchants (household_id, lower(name));

ALTER TABLE expenses ADD COLUMN merchant_id uuid;
ALTER TABLE expenses ADD CONSTRAINT expenses_merchant_fkey FOREIGN KEY (household_id, merchant_id)
    REFERENCES merchants (household_id, id) ON DELETE RESTRICT;
CREATE INDEX expenses_merchant ON expenses (merchant_id) WHERE merchant_id IS NOT NULL;

-- One merchant per name already in use (ignoring case and surrounding spaces), spelled as most recently written,
-- defaulting to the category its expenses use most. Moving expenses over isn't a change anyone made, so neither
-- step goes into the audit log: the merchants' trigger comes after, and the expenses' is off meanwhile.
INSERT INTO merchants (household_id, name, default_category_id)
SELECT household_id,
       (array_agg(trim(merchant) ORDER BY date DESC, created_at DESC))[1],
       mode() WITHIN GROUP (ORDER BY category_id)
FROM expenses
WHERE trim(merchant) <> ''
GROUP BY household_id, lower(trim(merchant));

ALTER TABLE expenses DISABLE TRIGGER audit;
UPDATE expenses e SET merchant_id = m.id
FROM merchants m
WHERE m.household_id = e.household_id AND lower(m.name) = lower(trim(e.merchant));
ALTER TABLE expenses ENABLE TRIGGER audit;

ALTER TABLE expenses DROP COLUMN merchant;

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON merchants
    FOR EACH ROW EXECUTE FUNCTION record_audit('merchant');

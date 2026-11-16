-- E-shop orders, read from the shop's emails (or entered by a parser some other way): what was bought, so the bank
-- row that paid for it can be split per item. An order matches at most one bank row; while that row is pending, the
-- order fills its split draft (V38) from the items' categories and whose. Once the row becomes an expense, the
-- order stays linked through the row, so the expense keeps its items apart from its split.

CREATE TABLE orders (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    -- The parser that read it, e.g. 'alza'; with number, what makes sending the same email again an update.
    shop            text        NOT NULL CHECK (shop <> ''),
    number          text        NOT NULL CHECK (number <> ''),
    ordered_on      date        NOT NULL,
    -- What the shop charged, in [currency], positive (negative for a refund of an order).
    total_minor     bigint      NOT NULL CHECK (total_minor <> 0),
    currency        text        NOT NULL,
    -- The merchant its expense goes to, e.g. 'Alza'.
    merchant        text        NOT NULL DEFAULT '',
    -- A row of the same household (checked by the API: bank_rows has no (household_id, id) key).
    bank_row_id     uuid        REFERENCES bank_rows (id) ON DELETE SET NULL,
    -- Matched by hand: the matcher leaves it alone.
    matched_by_hand boolean     NOT NULL DEFAULT false,
    -- The email (or pasted text) it was read from, to read again when the parser improves. Kept out of the audit log.
    raw             text,
    version         integer     NOT NULL DEFAULT 1,
    created_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, id),
    UNIQUE (household_id, shop, number),
    UNIQUE (bank_row_id)
);
CREATE INDEX orders_household_date ON orders (household_id, ordered_on DESC);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON orders
    FOR EACH ROW EXECUTE FUNCTION record_audit('order');

-- One line of an order: an item, shipping, a payment fee or a discount (negative). [amount_minor] is the line's
-- total in the order's currency. [category_id] and [paid_by_user_id] say where it goes once someone (or the
-- suggestion from earlier orders) decided; [assigned] tells a joint item (null whose) from one nobody decided yet.
CREATE TABLE order_items (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid        NOT NULL,
    order_id        uuid        NOT NULL,
    position        integer     NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('item', 'shipping', 'fee', 'discount')),
    name            text        NOT NULL,
    quantity        numeric     NOT NULL DEFAULT 1 CHECK (quantity > 0),
    amount_minor    bigint      NOT NULL,
    category_id     uuid,
    paid_by_user_id uuid        REFERENCES users (id) ON DELETE SET NULL,
    assigned        boolean     NOT NULL DEFAULT false,
    UNIQUE (order_id, position),
    FOREIGN KEY (household_id, order_id) REFERENCES orders (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE SET NULL (category_id)
);
-- Earlier items with the same name suggest a new item's category and whose.
CREATE INDEX order_items_name ON order_items (household_id, lower(name));

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON order_items
    FOR EACH ROW EXECUTE FUNCTION record_audit('order_item');

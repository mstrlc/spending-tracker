-- Split lines: an expense can have lines with their own category, amount and notes that add up to its total
-- (a supermarket receipt across Groceries and Drugstore, or an e-shop order filled in item by item).

CREATE TABLE expense_lines (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid    NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    expense_id   uuid    NOT NULL,
    sort_order   integer NOT NULL DEFAULT 0,
    category_id  uuid,
    amount_minor bigint  NOT NULL CHECK (amount_minor <> 0),
    notes        text    NOT NULL DEFAULT '',
    FOREIGN KEY (household_id, expense_id) REFERENCES expenses (household_id, id) ON DELETE CASCADE,
    FOREIGN KEY (household_id, category_id) REFERENCES categories (household_id, id) ON DELETE RESTRICT
);
CREATE INDEX expense_lines_expense ON expense_lines (expense_id, sort_order);
CREATE INDEX expense_lines_category ON expense_lines (category_id) WHERE category_id IS NOT NULL;

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON expense_lines
    FOR EACH ROW EXECUTE FUNCTION record_audit('expense_line');

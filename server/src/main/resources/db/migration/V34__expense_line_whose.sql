-- Whose per split line: one payment can hold one member's items, the other's and joint ones. Null is joint, as on
-- the expense. Existing lines take their expense's whose.

ALTER TABLE expense_lines ADD COLUMN paid_by_user_id uuid;
ALTER TABLE expense_lines ADD CONSTRAINT expense_lines_paid_by_fkey FOREIGN KEY (household_id, paid_by_user_id)
    REFERENCES household_members (household_id, user_id) ON UPDATE CASCADE ON DELETE SET NULL (paid_by_user_id);

UPDATE expense_lines l SET paid_by_user_id = e.paid_by_user_id
FROM expenses e
WHERE e.id = l.expense_id AND e.household_id = l.household_id AND e.paid_by_user_id IS NOT NULL;

CREATE INDEX expense_lines_paid_by ON expense_lines (household_id, paid_by_user_id) WHERE paid_by_user_id IS NOT NULL;

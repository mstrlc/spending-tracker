-- A starter set of income categories that says what kind of money it was (the source stays who paid):
-- Salary, Scholarship, Business, Side work, Family & gifts, Bank bonuses & interest, Other income.
-- Households given V42's set (the ones with "Other income") get V42's "Freelance" and "Interest" renamed and the rest added. A name another top-level
-- category already has is left alone.

CREATE TEMP TABLE income_set (name text, old_name text, sort_order int) ON COMMIT DROP;
INSERT INTO income_set VALUES
    ('Salary', NULL, 100),
    ('Scholarship', NULL, 101),
    ('Business', 'Freelance', 102),
    ('Side work', NULL, 103),
    ('Family & gifts', NULL, 104),
    ('Bank bonuses & interest', 'Interest', 105),
    ('Other income', NULL, 106);

UPDATE categories c
SET name = s.name, sort_order = s.sort_order, version = c.version + 1, updated_at = now()
FROM income_set s
WHERE s.old_name IS NOT NULL AND c.parent_id IS NULL AND c.kind = 'income' AND c.name = s.old_name
  AND NOT EXISTS (SELECT 1 FROM categories o WHERE o.household_id = c.household_id AND o.parent_id IS NULL AND lower(o.name) = lower(s.name));

INSERT INTO categories (household_id, name, kind, sort_order)
SELECT h.id, s.name, 'income', s.sort_order
FROM households h
CROSS JOIN income_set s
WHERE EXISTS (SELECT 1 FROM categories c WHERE c.household_id = h.id AND c.parent_id IS NULL AND c.kind = 'income' AND c.name = 'Other income')
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.household_id = h.id AND c.parent_id IS NULL AND lower(c.name) = lower(s.name))
  AND (s.old_name IS NULL OR NOT EXISTS (
      SELECT 1 FROM categories c WHERE c.household_id = h.id AND c.parent_id IS NULL AND c.kind = 'income' AND c.name = s.old_name));

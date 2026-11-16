-- V41 seeds income categories only for new households. Give every household without one the same starter set
-- (skipping a name a category of another kind already has), and file interest under "Interest", since the inbox
-- already names its source that way. Other income stays uncategorised: its source is a payer, not a kind of income.

INSERT INTO categories (household_id, name, kind, sort_order)
SELECT h.id, d.name, 'income', d.sort_order
FROM households h
CROSS JOIN (VALUES ('Salary', 100), ('Freelance', 101), ('Interest', 102), ('Other income', 103)) AS d (name, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.household_id = h.id AND c.kind = 'income')
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.household_id = h.id AND c.parent_id IS NULL AND lower(c.name) = lower(d.name));

UPDATE income i
SET category_id = c.id, version = i.version + 1, updated_at = now()
FROM categories c
WHERE c.household_id = i.household_id AND c.parent_id IS NULL AND c.kind = 'income' AND c.name = 'Interest'
  AND i.kind = 'earning' AND i.category_id IS NULL AND lower(i.source) = 'interest';

CREATE INDEX income_category ON income (category_id) WHERE category_id IS NOT NULL;

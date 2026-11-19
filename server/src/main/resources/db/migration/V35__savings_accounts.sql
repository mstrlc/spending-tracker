-- A savings account apart from a current account ('bank'): its interest arrives monthly with tax already withheld
-- (15% in Czechia), so the inbox can suggest the interest as income worked back to the gross with the tax as a
-- deduction. interest_tax_percent is what the bank withholds; only savings accounts carry one.

ALTER TABLE accounts DROP CONSTRAINT accounts_kind_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_kind_check CHECK (kind IN ('bank', 'savings', 'credit_card', 'cash', 'other'));
ALTER TABLE accounts ADD COLUMN interest_tax_percent integer
    CHECK (interest_tax_percent IS NULL OR (interest_tax_percent BETWEEN 0 AND 99 AND kind = 'savings'));

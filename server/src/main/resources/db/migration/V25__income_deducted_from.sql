-- A deduction can point at the earning it came off (a payslip). The deductions go with the earning: without it
-- they'd lower the month's net income with no gross to come off, e.g. when a payslip made from a bank row is put
-- back in the inbox.
ALTER TABLE income
    ADD COLUMN deducted_from uuid,
    ADD FOREIGN KEY (household_id, deducted_from) REFERENCES income (household_id, id) ON DELETE CASCADE;
CREATE INDEX income_deducted_from ON income (deducted_from) WHERE deducted_from IS NOT NULL;

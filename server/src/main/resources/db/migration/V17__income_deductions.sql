-- Income is recorded gross; what comes off it is a deduction: health and social insurance advances (ZPMV, OSSZ),
-- income tax. A deduction is an income line of its own, with the amount taken off as a positive number (negative
-- for an overpaid advance refunded), the month it's for and who it's for. Earned, deducted and net are all visible,
-- and net is what reports count as income. Deductions are never spending.

ALTER TABLE income ADD COLUMN kind text NOT NULL DEFAULT 'earning' CHECK (kind IN ('earning', 'deduction'));

-- The old app recorded the insurance advances as negative income lines; the import brought them across as such.
UPDATE income SET kind = 'deduction', amount_minor = -amount_minor
WHERE external_ref LIKE 'legacy:income:%' AND source IN ('ZPMV', 'OSSZ') AND amount_minor < 0;

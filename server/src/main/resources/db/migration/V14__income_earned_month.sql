-- Income has two dates: the month it was earned, which reports count it in, and the date it arrived, which links it
-- to the bank row. October's salary paid on 2 November counts for October. Stored as the month's first day.

ALTER TABLE income ADD COLUMN earned_month date;
UPDATE income SET earned_month = date_trunc('month', date)::date;
ALTER TABLE income ALTER COLUMN earned_month SET NOT NULL;
ALTER TABLE income ADD CONSTRAINT income_earned_month_first_day CHECK (extract(day FROM earned_month) = 1);
CREATE INDEX income_household_earned ON income (household_id, earned_month DESC);

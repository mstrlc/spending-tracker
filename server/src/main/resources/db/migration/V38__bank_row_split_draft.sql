-- A pending row can carry the expense it will become, prepared ahead (from an e-shop order's email, say) while it
-- stays in the inbox: the lines with their category, amount and whose, a merchant, notes and where it came from.
-- Filing the row still goes through the expense form, which starts from the draft. JSON (SplitDraft in shared).

ALTER TABLE bank_rows ADD COLUMN split_draft jsonb;

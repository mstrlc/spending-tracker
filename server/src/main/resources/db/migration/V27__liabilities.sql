-- Liabilities (loans, credit card debt, money the household owes) share the assets table so contributions,
-- valuations and fees carry over. Valuations still store what's owed as >= 0; the API reports liability
-- standing as negative so summing every asset's valueMinor gives net worth.

ALTER TABLE assets DROP CONSTRAINT assets_kind_check;
ALTER TABLE assets ADD CONSTRAINT assets_kind_check CHECK (kind IN (
    'fund', 'stock', 'bond', 'crypto',     -- market: can follow a listed security
    'savings', 'scheme',                   -- savings accounts; building savings, pension and other locked schemes
    'receivable', 'vehicle', 'property',   -- valued by hand
    'loan', 'card_debt', 'payable',        -- liabilities (valuations store what's owed, >= 0)
    'other'
));

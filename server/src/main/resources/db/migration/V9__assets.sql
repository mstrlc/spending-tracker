-- Investments become assets: anything the household owns that has a worth, not just market holdings.
-- Savings and savings schemes (building savings, pension), money owed to the household, vehicles and property
-- join the market kinds. They all share contributions, valuations and fees.

ALTER TABLE investments RENAME TO assets;
ALTER TABLE investment_contributions RENAME TO asset_contributions;
ALTER TABLE investment_valuations RENAME TO asset_valuations;
ALTER TABLE investment_fees RENAME TO asset_fees;

ALTER TABLE asset_contributions RENAME COLUMN investment_id TO asset_id;
ALTER TABLE asset_valuations RENAME COLUMN investment_id TO asset_id;
ALTER TABLE asset_fees RENAME COLUMN investment_id TO asset_id;

ALTER INDEX investments_household RENAME TO assets_household;
ALTER INDEX investment_contributions_investment RENAME TO asset_contributions_asset;
ALTER INDEX investment_contributions_household_date RENAME TO asset_contributions_household_date;
ALTER INDEX investment_fees_investment RENAME TO asset_fees_asset;

ALTER TABLE assets DROP CONSTRAINT investments_kind_check;
ALTER TABLE assets ADD CONSTRAINT assets_kind_check CHECK (kind IN (
    'fund', 'stock', 'bond', 'crypto',     -- market: can follow a listed security
    'savings', 'scheme',                   -- savings accounts; building savings, pension and other locked schemes
    'receivable', 'vehicle', 'property',   -- valued by hand
    'other'
));

-- The audit trigger names the entity; keep the log's past rows in step so history reads as one thing.
DROP TRIGGER audit ON assets;
DROP TRIGGER audit ON asset_contributions;
DROP TRIGGER audit ON asset_valuations;
DROP TRIGGER audit ON asset_fees;
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON assets
    FOR EACH ROW EXECUTE FUNCTION record_audit('asset');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON asset_contributions
    FOR EACH ROW EXECUTE FUNCTION record_audit('asset_contribution');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON asset_valuations
    FOR EACH ROW EXECUTE FUNCTION record_audit('asset_valuation');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON asset_fees
    FOR EACH ROW EXECUTE FUNCTION record_audit('asset_fee');

UPDATE audit_log SET entity = 'asset' WHERE entity = 'investment';
UPDATE audit_log SET entity = 'asset_contribution' WHERE entity = 'investment_contribution';
UPDATE audit_log SET entity = 'asset_valuation' WHERE entity = 'investment_valuation';
UPDATE audit_log SET entity = 'asset_fee' WHERE entity = 'investment_fee';
-- Rows from before the rename still say investment_id inside their before/after JSON.
UPDATE audit_log
SET before = CASE WHEN before ? 'investment_id'
                  THEN (before - 'investment_id') || jsonb_build_object('asset_id', before -> 'investment_id') ELSE before END,
    after  = CASE WHEN after ? 'investment_id'
                  THEN (after - 'investment_id') || jsonb_build_object('asset_id', after -> 'investment_id') ELSE after END
WHERE entity IN ('asset_contribution', 'asset_valuation', 'asset_fee');

-- What a provider charged on a holding: management fees, commissions, less any discounts (negative).
-- Recorded for information: the provider takes them out of the holding, so they are already in its value,
-- and they are not money put in.

CREATE TABLE investment_fees (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    investment_id uuid        NOT NULL,
    date          date        NOT NULL,
    -- Minor units of the household currency; negative for a discount or refund.
    amount_minor  bigint      NOT NULL CHECK (amount_minor <> 0),
    notes         text        NOT NULL DEFAULT '',
    version       integer     NOT NULL DEFAULT 1,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (household_id, investment_id) REFERENCES investments (household_id, id) ON DELETE CASCADE
);
CREATE INDEX investment_fees_investment ON investment_fees (investment_id, date DESC);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON investment_fees
    FOR EACH ROW EXECUTE FUNCTION record_audit('investment_fee');

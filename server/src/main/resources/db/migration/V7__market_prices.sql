-- Market valuation: a holding can follow a listed security, and contributions can say how many shares they bought,
-- so its value comes from daily closing prices instead of hand-entered valuations.
-- Securities, prices and exchange rates are public market data shared by every household: no household_id, no audit.

CREATE TABLE securities (
    -- The market-data provider's symbol, e.g. VWCE.DE (Vanguard FTSE All-World on Xetra).
    symbol   text PRIMARY KEY,
    name     text NOT NULL,
    exchange text NOT NULL DEFAULT '',
    -- ISO code of the currency the security is quoted in.
    currency text NOT NULL
);

CREATE TABLE security_prices (
    symbol text    NOT NULL REFERENCES securities (symbol) ON DELETE CASCADE,
    date   date    NOT NULL,
    close  numeric NOT NULL CHECK (close >= 0),
    PRIMARY KEY (symbol, date)
);

-- ČNB fixings: CZK for one unit of [currency] on [date] (business days only).
CREATE TABLE fx_rates (
    currency text    NOT NULL,
    date     date    NOT NULL,
    czk      numeric NOT NULL CHECK (czk > 0),
    PRIMARY KEY (currency, date)
);

-- When each piece of market data was last fetched ('prices:VWCE.DE', 'fx:2024'), so requests don't refetch it.
CREATE TABLE market_fetches (
    key        text PRIMARY KEY,
    fetched_at timestamptz NOT NULL
);

ALTER TABLE investments ADD COLUMN symbol text REFERENCES securities (symbol);
-- Shares bought (negative: sold). NULL for contributions recorded without them, which count at cost.
ALTER TABLE investment_contributions ADD COLUMN quantity numeric CHECK (quantity IS NULL OR quantity <> 0);

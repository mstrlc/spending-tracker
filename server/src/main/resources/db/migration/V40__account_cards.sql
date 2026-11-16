-- The last four digits of an account's payment cards, space-separated: other banks' rows name the card that paid
-- ("Apple Pay Top-Up by *3656" on Revolut), which tells the inbox which account the money came from. A card belongs to
-- one account of the household; the API checks that.

ALTER TABLE accounts ADD COLUMN cards text NOT NULL DEFAULT ''
    CHECK (cards ~ '^([0-9]{4}( [0-9]{4})*)?$');

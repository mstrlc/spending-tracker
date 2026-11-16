-- Whether a decided row teaches the inbox: suggestions for later rows with the same descriptor (merchant, category,
-- whose, income source, asset) come from the latest row that does. Off for a one-off, such as paying a friend back
-- for one dinner, so that friend's account doesn't come to mean that restaurant.

ALTER TABLE bank_rows ADD COLUMN remember boolean NOT NULL DEFAULT true;

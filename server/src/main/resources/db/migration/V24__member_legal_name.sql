-- Optional legal name per household member so bank transfer hints match "SURNAME NAME" as well as display names.
ALTER TABLE household_members
    ADD COLUMN legal_name text NOT NULL DEFAULT '';

-- Every create, update and delete of household data, with the row before and after as JSON and who did it.
-- Written by triggers rather than application code, so no route can forget it. The acting user comes from
-- the transaction-local setting app.actor_id, which the server sets at the start of each request transaction.

CREATE TABLE audit_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id  uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    at            timestamptz NOT NULL DEFAULT now(),
    -- NULL: a change made outside a request (a migration, a script) or by a user since deleted.
    actor_user_id uuid        REFERENCES users (id) ON DELETE SET NULL,
    -- The table, singular: 'account', 'category', 'expense', 'income', 'member', 'invite', 'household'.
    entity        text        NOT NULL,
    -- The row's id; for members, the user id.
    entity_id     uuid        NOT NULL,
    action        text        NOT NULL CHECK (action IN ('create', 'update', 'delete')),
    before        jsonb,
    after         jsonb
);
CREATE INDEX audit_log_household ON audit_log (household_id, id DESC);
CREATE INDEX audit_log_entity ON audit_log (household_id, entity, entity_id, id DESC);

CREATE FUNCTION record_audit() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    -- Secrets never go into the log.
    old_row jsonb := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - 'token_hash' END;
    new_row jsonb := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - 'token_hash' END;
    row_data jsonb := coalesce(new_row, old_row);
    household uuid := coalesce(row_data ->> 'household_id', row_data ->> 'id')::uuid;
BEGIN
    -- Bookkeeping-only updates (nothing but updated_at or version moved) aren't changes worth listing.
    IF TG_OP = 'UPDATE' AND (old_row - 'updated_at' - 'version') = (new_row - 'updated_at' - 'version') THEN
        RETURN NULL;
    END IF;
    -- Rows deleted along with their household have nothing left to belong to.
    IF NOT EXISTS (SELECT 1 FROM households WHERE id = household) THEN
        RETURN NULL;
    END IF;
    INSERT INTO audit_log (household_id, actor_user_id, entity, entity_id, action, before, after)
    VALUES (
        household,
        nullif(current_setting('app.actor_id', true), '')::uuid,
        TG_ARGV[0],
        coalesce(row_data ->> 'id', row_data ->> 'user_id')::uuid,
        CASE TG_OP WHEN 'INSERT' THEN 'create' WHEN 'UPDATE' THEN 'update' ELSE 'delete' END,
        old_row,
        new_row
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON households
    FOR EACH ROW EXECUTE FUNCTION record_audit('household');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON household_members
    FOR EACH ROW EXECUTE FUNCTION record_audit('member');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON household_invites
    FOR EACH ROW EXECUTE FUNCTION record_audit('invite');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION record_audit('account');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON categories
    FOR EACH ROW EXECUTE FUNCTION record_audit('category');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON expenses
    FOR EACH ROW EXECUTE FUNCTION record_audit('expense');
CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON income
    FOR EACH ROW EXECUTE FUNCTION record_audit('income');

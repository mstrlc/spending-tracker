-- Trips: a name and optional dates; expenses can belong to one (including a single lump sum booked for the trip).

CREATE TABLE trips (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid        NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    name         text        NOT NULL CHECK (length(trim(name)) > 0),
    start_date   date,
    end_date     date,
    notes        text        NOT NULL DEFAULT '',
    version      integer     NOT NULL DEFAULT 1,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, name),
    UNIQUE (household_id, id),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TRIGGER audit AFTER INSERT OR UPDATE OR DELETE ON trips
    FOR EACH ROW EXECUTE FUNCTION record_audit('trip');

ALTER TABLE expenses ADD COLUMN trip_id uuid;
ALTER TABLE expenses ADD CONSTRAINT expenses_trip_fkey FOREIGN KEY (household_id, trip_id)
    REFERENCES trips (household_id, id) ON DELETE RESTRICT;
CREATE INDEX expenses_trip ON expenses (trip_id) WHERE trip_id IS NOT NULL;

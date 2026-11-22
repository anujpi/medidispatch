-- V2: hospitals and clinics, units' bases, and the transport leg (scene to hospital).
-- V1 has run everywhere already, so it is never edited: every change to the schema lands in a new file like this.
-- Flyway runs one migration in one transaction: if any statement fails, all of V2 is undone.

-- Facilities (port: FacilityRepository). A handful per city, rarely changed (diversion on/off).
CREATE TABLE facilities (
    id           text PRIMARY KEY,
    name         text NOT NULL,
    kind         text NOT NULL CHECK (kind IN ('HOSPITAL', 'CLINIC')),
    location     geography(Point, 4326) NOT NULL,
    -- An array, not a facility_capabilities table: a facility is always read whole, and the chooser filters in Java.
    -- <@ = "is contained in", so only known capability names fit.
    capabilities text[] NOT NULL DEFAULT '{}'
                 CHECK (capabilities <@ ARRAY['CARDIAC', 'STROKE', 'TRAUMA', 'PAEDIATRIC']),
    accepting    boolean NOT NULL,
    -- The same rule as the Facility record: a clinic has no emergency department.
    CHECK (kind = 'HOSPITAL' OR (cardinality(capabilities) = 0 AND NOT accepting))
);

-- New columns on tables that already hold rows. Nullable with no default (or a constant default), so Postgres only
-- changes the table's description and rewrites no rows: instant even on a big table. Old rows get NULL / '{}'.

-- The facility a unit belongs to; NULL = no base.
ALTER TABLE units ADD COLUMN base_id text REFERENCES facilities (id);

-- Where the patient is taken, and what the crew said they need (for the hospital's pre-alert).
ALTER TABLE assignments ADD COLUMN hospital_id text REFERENCES facilities (id);
ALTER TABLE assignments ADD COLUMN needs text[] NOT NULL DEFAULT '{}'
    CHECK (needs <@ ARRAY['CARDIAC', 'STROKE', 'TRAUMA', 'PAEDIATRIC']);
-- The same rule as Assignment: a transport always knows its hospital.
ALTER TABLE assignments ADD CONSTRAINT transport_has_hospital CHECK (status <> 'TRANSPORTING' OR hospital_id IS NOT NULL);

-- A CHECK cannot be edited, only dropped and added again. V1's column CHECKs got Postgres's automatic names,
-- <table>_<column>_check. Both statements are one ALTER TABLE, so there is never a moment without the rule.
ALTER TABLE cases
    DROP CONSTRAINT cases_status_check,
    ADD CONSTRAINT cases_status_check
        CHECK (status IN ('MONITORING', 'ALERTED', 'RESPONDING', 'TRANSPORTING', 'STABILISED', 'CLOSED', 'CANCELLED'));

ALTER TABLE assignments
    DROP CONSTRAINT assignments_status_check,
    ADD CONSTRAINT assignments_status_check
        CHECK (status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED', 'TRANSPORTING', 'DONE', 'CANCELLED', 'TIMED_OUT', 'FAILED'));

-- A unit carrying a patient is on a trip: without TRANSPORTING here, the database would allow it a second active
-- trip. A partial index's WHERE cannot be edited either, so it is rebuilt (inside this migration's transaction).
DROP INDEX one_active_trip_per_unit;
CREATE UNIQUE INDEX one_active_trip_per_unit ON assignments (unit_id)
    WHERE status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED', 'TRANSPORTING');

-- ponytail: no index for "patients coming to hospital X": it reads only active trips (tens). Add
-- (hospital_id) WHERE status = 'TRANSPORTING' if the hospital screen ever gets slow.

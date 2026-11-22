-- V1: the first schema. Flyway runs this file once and records it (with a checksum) in flyway_schema_history.
-- NEVER edit it after it has run anywhere: put every change in a new file (V2__..., V3__...).
--
-- Conventions:
--   * ids are text: the engine already uses String ids (UUIDs for cases and trips).
--   * enum values are text + CHECK: readable in any SQL tool, simple with JDBC, a new value = one new migration.
--   * places are geography(Point, 4326): 4326 = GPS coordinates; distances come out in metres on the real Earth.
--     PostGIS takes LONGITUDE FIRST: ST_Point(lon, lat, 4326).
--   * times are timestamptz (an exact instant); plain timestamp drops the zone and would shift Instants.
-- ponytail: no version columns (optimistic locking): DispatchService's one lock serialises every write today.
-- Add them, with a two-thread test that loses an update first, when that lock goes away.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Patients: rarely change.
CREATE TABLE patients (
    id   text PRIMARY KEY,
    home geography(Point, 4326) NOT NULL
);

-- Cases (port: CaseRepository, and CaseQueue through the queued flag).
CREATE TABLE cases (
    id         text PRIMARY KEY,
    patient_id text NOT NULL REFERENCES patients (id),
    status     text NOT NULL CHECK (status IN ('MONITORING', 'ALERTED', 'RESPONDING', 'STABILISED', 'CLOSED', 'CANCELLED')),
    band       text NOT NULL CHECK (band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    opened_at  timestamptz NOT NULL,
    -- true while the case waits in the dispatch queue (enqueue sets it, pollNext/remove clear it)
    queued     boolean NOT NULL DEFAULT false
);

-- One open case per patient, enforced by the database. A partial index covers only rows matching its WHERE,
-- so this is "unique among open cases" (CaseStatus.isTerminal: CLOSED, CANCELLED). It also serves findOpen().
CREATE UNIQUE INDEX one_open_case_per_patient ON cases (patient_id) WHERE status NOT IN ('CLOSED', 'CANCELLED');

-- Only the few waiting cases: closed cases pile up forever, the queue scan must never read them.
CREATE INDEX queued_cases ON cases (opened_at) WHERE queued;

-- Units (port: ResourceRepository; ResourceClaimer through claimed_by). Positions are overwritten every few seconds.
CREATE TABLE units (
    id            text PRIMARY KEY,
    type          text NOT NULL CHECK (type IN ('AMBULANCE_ALS', 'AMBULANCE_BLS', 'NURSE', 'COURIER', 'DOCTOR')),
    status        text NOT NULL CHECK (status IN ('AVAILABLE', 'ASSIGNED', 'OFFLINE')),
    position      geography(Point, 4326) NOT NULL,
    -- where the unit was when it last counted as "moved" (UnitTracker): engine state, must survive a restart
    moved_from    geography(Point, 4326) NOT NULL,
    -- NULL = never
    last_seen_at  timestamptz,
    last_moved_at timestamptz,
    -- The claim. Claiming is one atomic statement, safe across threads AND across service copies:
    --   UPDATE units SET claimed_by = :case WHERE id = :unit AND (claimed_by IS NULL OR claimed_by = :case)
    -- 1 row updated = won; 0 = another case holds it. Postgres locks the row, so a second claimer waits,
    -- then re-checks the WHERE and finds it taken.
    claimed_by    text REFERENCES cases (id)
);

-- Spatial index for "units within X km". A B-tree sorts along one line; GiST groups points into nested boxes,
-- so ST_DWithin only opens the boxes near the patient.
CREATE INDEX units_position ON units USING gist (position);

-- Trips (port: AssignmentRepository).
CREATE TABLE assignments (
    id          text PRIMARY KEY,
    case_id     text NOT NULL REFERENCES cases (id),
    unit_id     text NOT NULL REFERENCES units (id),
    role        text NOT NULL CHECK (role IN ('PRIMARY', 'SUPPORT')),
    status      text NOT NULL CHECK (status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED', 'DONE', 'CANCELLED', 'TIMED_OUT', 'FAILED')),
    assigned_at timestamptz NOT NULL
);

-- findActiveByCaseId
CREATE INDEX assignments_by_case ON assignments (case_id);

-- One active trip per unit, enforced by the database (the non-terminal statuses of AssignmentStatus).
-- Also serves findActive() and findActiveByResourceId().
CREATE UNIQUE INDEX one_active_trip_per_unit ON assignments (unit_id) WHERE status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED');

-- Vitals readings (port: VitalsRepository): append-only, the heavy table (design load ~830 inserts/s).
-- Measurements are nullable: a wearable may not measure everything.
CREATE TABLE vitals (
    patient_id    text NOT NULL REFERENCES patients (id),
    at            timestamptz NOT NULL,
    heart_rate    integer,
    spo2          integer,
    systolic_bp   integer,
    diastolic_bp  integer,
    resp_rate     integer,
    temperature   double precision,
    consciousness text CHECK (consciousness IN ('ALERT', 'CONFUSED', 'VOICE', 'PAIN', 'UNRESPONSIVE')),
    -- TimescaleDB requires every unique key to include the time column. This one is also the duplicate detector
    -- (same patient, same timestamp = refused) and serves latestFor: WHERE patient_id = ? ORDER BY at DESC LIMIT 1.
    PRIMARY KEY (patient_id, at)
);

-- Turn vitals into a hypertable: stored as one chunk per day. Today's inserts hit a small chunk whose index fits
-- in memory; dropping old data later means dropping whole chunks instead of deleting rows one by one.
-- ponytail: no compression, retention or 5-minute aggregates yet (PLAN section 5); add them in their own
-- migration once there is real data to keep.
SELECT create_hypertable('vitals', by_range('at', INTERVAL '1 day'));

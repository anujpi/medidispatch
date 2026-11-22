-- V3: the people behind the data: who drives each unit, who each patient is, and how to reach a hospital.
-- The engine never uses these to dispatch; the control room and the hospitals do.
--
-- Every new column allows NULL: rows from before V3 (for example a unit registered in a demo) have none of this,
-- and NOT NULL cannot be added over them. The service's API requires these values for every NEW row.
-- ponytail: once old rows are filled in or removed, a later migration can make them NOT NULL.

-- The driver: name and phone come together or not at all.
ALTER TABLE units
    ADD COLUMN driver_name  text,
    ADD COLUMN driver_phone text,
    ADD CONSTRAINT driver_complete CHECK ((driver_name IS NULL) = (driver_phone IS NULL));

-- Who the patient is (name, age and sex come together), and whom to call (name and phone together).
ALTER TABLE patients
    ADD COLUMN name            text,
    ADD COLUMN age             integer CHECK (age BETWEEN 0 AND 120),
    ADD COLUMN sex             text CHECK (sex IN ('FEMALE', 'MALE', 'OTHER')),
    ADD COLUMN emergency_name  text,
    ADD COLUMN emergency_phone text,
    ADD CONSTRAINT details_complete CHECK ((name IS NULL) = (age IS NULL) AND (name IS NULL) = (sex IS NULL)),
    ADD CONSTRAINT emergency_contact_complete CHECK ((emergency_name IS NULL) = (emergency_phone IS NULL));

-- How the control room reaches a hospital or clinic.
ALTER TABLE facilities ADD COLUMN phone text;

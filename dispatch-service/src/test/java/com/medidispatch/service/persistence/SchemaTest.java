package com.medidispatch.service.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the schema in {@code db/migration} enforces the rules itself, on a REAL Postgres + PostGIS +
 * TimescaleDB, using plain SQL (no Spring, no adapters yet).
 *
 * <p>The database is the shared test container from {@link TestDatabase} (started and migrated once per run;
 * needs Docker running), emptied before every test.
 *
 * <p>Error codes checked here are standard SQLStates: {@code 23505} = unique violation, {@code 23514} = check
 * violation, {@code 23503} = foreign key violation.
 */
class SchemaTest {

    private static final double DELHI_LAT = 28.6139;
    private static final double DELHI_LON = 77.2090;

    /** The claim (ResourceClaimer.tryClaim): 1 row updated = won, 0 = another case holds the unit. */
    private static final String CLAIM =
            "UPDATE units SET claimed_by = ? WHERE id = ? AND (claimed_by IS NULL OR claimed_by = ?)";

    /** The release (ResourceClaimer.release): only the case holding the unit can let it go. */
    private static final String RELEASE =
            "UPDATE units SET claimed_by = NULL WHERE id = ? AND claimed_by = ?";

    /** Shared connection in auto-commit mode: every statement is its own transaction. */
    private static Connection conn;

    /** Opens the shared connection (the first database test of the run also starts the container). */
    @BeforeAll
    static void openConnection() throws SQLException {
        conn = connect();
    }

    /** Closes the shared connection; the container itself lives until the end of the run. */
    @AfterAll
    static void closeConnection() throws SQLException {
        conn.close();
    }

    /** Every test starts from empty tables. */
    @BeforeEach
    void emptyTables() {
        TestDatabase.reset();
    }

    /**
     * The claim lesson, live: connection {@code a} claims inside an open transaction, so Postgres holds a lock on
     * the unit's row. Connection {@code b} (another thread) tries to claim the same unit and WAITS on that lock;
     * when {@code a} commits, {@code b} re-checks the WHERE, finds the unit taken and updates 0 rows.
     * No Java lock anywhere: the database is the referee.
     */
    @Test
    void whenTwoCasesClaimOneUnitTheSecondWaitsThenLoses() throws Exception {
        patient("p-race-1");
        patient("p-race-2");
        openCase("c-race-1", "p-race-1");
        openCase("c-race-2", "p-race-2");
        unit("u-race", DELHI_LAT, DELHI_LON);

        ExecutorService other = Executors.newSingleThreadExecutor();
        try (Connection a = connect(); Connection b = connect()) {
            a.setAutoCommit(false);
            assertEquals(1, update(a, CLAIM, "c-race-1", "u-race", "c-race-1"));

            Future<Integer> bTries = other.submit(() -> update(b, CLAIM, "c-race-2", "u-race", "c-race-2"));
            // b is stuck on a's row lock, not finished.
            Thread.sleep(300);
            assertFalse(bTries.isDone());

            a.commit();
            assertEquals(0, bTries.get(5, TimeUnit.SECONDS));
        } finally {
            other.shutdown();
        }
        assertEquals(List.of("c-race-1"), strings("SELECT claimed_by FROM units WHERE id = ?", "u-race"));
    }

    /** Same semantics as InMemoryResourceClaimer: re-claiming your own unit is fine; only the holder releases. */
    @Test
    void claimingAgainIsHarmlessAndOnlyTheHolderCanRelease() throws SQLException {
        patient("p-hold-1");
        patient("p-hold-2");
        openCase("c-hold-1", "p-hold-1");
        openCase("c-hold-2", "p-hold-2");
        unit("u-hold", DELHI_LAT, DELHI_LON);

        assertEquals(1, update(conn, CLAIM, "c-hold-1", "u-hold", "c-hold-1"));
        assertEquals(1, update(conn, CLAIM, "c-hold-1", "u-hold", "c-hold-1"));
        assertEquals(0, update(conn, RELEASE, "u-hold", "c-hold-2"));
        assertEquals(1, update(conn, RELEASE, "u-hold", "c-hold-1"));
        assertEquals(1, update(conn, CLAIM, "c-hold-2", "u-hold", "c-hold-2"));
    }

    /** The partial unique index: a second OPEN case is refused; once the first is closed, a new one is fine. */
    @Test
    void aPatientCannotHaveTwoOpenCases() throws SQLException {
        patient("p-open");
        openCase("c-open-1", "p-open");

        SQLException refused = assertThrows(SQLException.class, () -> openCase("c-open-2", "p-open"));
        assertEquals("23505", refused.getSQLState());

        update(conn, "UPDATE cases SET status = 'CLOSED' WHERE id = ?", "c-open-1");
        openCase("c-open-2", "p-open");
    }

    /** The partial unique index: a busy unit cannot get a second active trip; once the first is done, it can. */
    @Test
    void aUnitCannotHaveTwoActiveTrips() throws SQLException {
        patient("p-trip-1");
        patient("p-trip-2");
        openCase("c-trip-1", "p-trip-1");
        openCase("c-trip-2", "p-trip-2");
        unit("u-trip", DELHI_LAT, DELHI_LON);
        trip("t-trip-1", "c-trip-1", "u-trip", "EN_ROUTE");

        SQLException refused = assertThrows(SQLException.class, () -> trip("t-trip-2", "c-trip-2", "u-trip", "ASSIGNED"));
        assertEquals("23505", refused.getSQLState());

        update(conn, "UPDATE assignments SET status = 'DONE' WHERE id = ?", "t-trip-1");
        trip("t-trip-2", "c-trip-2", "u-trip", "ASSIGNED");
    }

    /** The CHECK constraints: a value that is not one of the Java enum's is refused. */
    @Test
    void anUnknownStatusIsRefused() {
        SQLException refused = assertThrows(SQLException.class, () -> update(conn,
                "INSERT INTO units (id, type, status, position, moved_from) "
                        + "VALUES ('u-bad', 'NURSE', 'ON_FIRE', ST_Point(?, ?, 4326)::geography, ST_Point(?, ?, 4326)::geography)",
                DELHI_LON, DELHI_LAT, DELHI_LON, DELHI_LAT));
        assertEquals("23514", refused.getSQLState());
    }

    /**
     * The vitals primary key (patient, time): the same reading twice is refused, and "latest reading" is one
     * ORDER BY ... LIMIT 1 served by that key. The driver maps timestamptz to {@link OffsetDateTime}.
     */
    @Test
    void theSameReadingTwiceIsADuplicate() throws SQLException {
        patient("p-vitals");
        OffsetDateTime at = OffsetDateTime.parse("2026-11-21T10:00:00Z");
        String insert = "INSERT INTO vitals (patient_id, at, heart_rate) VALUES (?, ?, ?)";
        update(conn, insert, "p-vitals", at, 80);

        SQLException refused = assertThrows(SQLException.class, () -> update(conn, insert, "p-vitals", at, 80));
        assertEquals("23505", refused.getSQLState());

        update(conn, insert, "p-vitals", at.plusMinutes(1), 82);
        assertEquals(List.of("82"), strings(
                "SELECT heart_rate FROM vitals WHERE patient_id = ? ORDER BY at DESC LIMIT 1", "p-vitals"));
    }

    /** create_hypertable ran: TimescaleDB lists vitals as one of its hypertables. */
    @Test
    void vitalsIsAHypertable() throws SQLException {
        assertEquals(List.of("vitals"), strings(
                "SELECT hypertable_name FROM timescaledb_information.hypertables WHERE hypertable_name = ?", "vitals"));
    }

    /**
     * "Units within 5 km" with ST_DWithin (geography measures in metres). 0.018 degrees of latitude is about 2 km;
     * checking ST_Distance is about 2000 m also proves longitude-first is right (swapped, the point would move).
     */
    @Test
    void nearbyFindsAUnitTwoKmAwayButNotOneTwentyKmAway() throws SQLException {
        unit("near-2km", DELHI_LAT + 0.018, DELHI_LON);
        unit("near-20km", DELHI_LAT + 0.18, DELHI_LON);

        List<String> within5km = strings(
                "SELECT id FROM units WHERE id LIKE 'near-%' "
                        + "AND ST_DWithin(position, ST_Point(?, ?, 4326)::geography, ?) ORDER BY id",
                DELHI_LON, DELHI_LAT, 5000);
        assertEquals(List.of("near-2km"), within5km);

        double metres = Double.parseDouble(strings(
                "SELECT ST_Distance(position, ST_Point(?, ?, 4326)::geography) FROM units WHERE id = ?",
                DELHI_LON, DELHI_LAT, "near-2km").get(0));
        assertEquals(2000, metres, 50);
    }

    // ---- V2: hospitals, bases, transport ----

    /** The clinic rule, in the database too: no emergency department means no capabilities and never accepting. */
    @Test
    void aClinicThatAcceptsPatientsOrClaimsServicesIsRefused() throws SQLException {
        facility("c-ok", "CLINIC", "{}", false);

        SQLException accepting = assertThrows(SQLException.class, () -> facility("c-bad-1", "CLINIC", "{}", true));
        SQLException stroke = assertThrows(SQLException.class, () -> facility("c-bad-2", "CLINIC", "{STROKE}", false));
        assertEquals("23514", accepting.getSQLState());
        assertEquals("23514", stroke.getSQLState());
    }

    @Test
    void anUnknownCapabilityIsRefused() throws SQLException {
        facility("h-ok", "HOSPITAL", "{STROKE,CARDIAC}", true);

        SQLException refused = assertThrows(SQLException.class, () -> facility("h-bad", "HOSPITAL", "{DENTIST}", true));
        assertEquals("23514", refused.getSQLState());
    }

    /** units.base_id is a foreign key: a unit can only belong to a facility that exists. */
    @Test
    void aUnitsBaseMustExist() throws SQLException {
        facility("h-base", "HOSPITAL", "{}", true);
        unit("u-based", DELHI_LAT, DELHI_LON);

        assertEquals(1, update(conn, "UPDATE units SET base_id = ? WHERE id = ?", "h-base", "u-based"));
        SQLException refused = assertThrows(SQLException.class,
                () -> update(conn, "UPDATE units SET base_id = ? WHERE id = ?", "h-nowhere", "u-based"));
        assertEquals("23503", refused.getSQLState());
    }

    /** The same rule as Assignment: a transport always knows its hospital. Cases accept TRANSPORTING too. */
    @Test
    void aTransportNeedsAHospital() throws SQLException {
        patient("p-move");
        openCase("c-move", "p-move");
        unit("u-move", DELHI_LAT, DELHI_LON);
        facility("h-move", "HOSPITAL", "{}", true);
        trip("t-move", "c-move", "u-move", "ARRIVED");

        SQLException refused = assertThrows(SQLException.class,
                () -> update(conn, "UPDATE assignments SET status = 'TRANSPORTING' WHERE id = ?", "t-move"));
        assertEquals("23514", refused.getSQLState());

        assertEquals(1, update(conn, "UPDATE assignments SET status = 'TRANSPORTING', hospital_id = ?, "
                + "needs = '{STROKE}' WHERE id = ?", "h-move", "t-move"));
        assertEquals(1, update(conn, "UPDATE cases SET status = 'TRANSPORTING' WHERE id = ?", "c-move"));
    }

    /** The rebuilt partial index: an ambulance carrying a patient is on a trip, so it cannot get a second one. */
    @Test
    void aUnitCarryingAPatientCannotGetASecondTrip() throws SQLException {
        patient("p-carry-1");
        patient("p-carry-2");
        openCase("c-carry-1", "p-carry-1");
        openCase("c-carry-2", "p-carry-2");
        unit("u-carry", DELHI_LAT, DELHI_LON);
        facility("h-carry", "HOSPITAL", "{}", true);
        update(conn, "INSERT INTO assignments (id, case_id, unit_id, role, status, assigned_at, hospital_id) "
                + "VALUES ('t-carry-1', 'c-carry-1', 'u-carry', 'PRIMARY', 'TRANSPORTING', now(), 'h-carry')");

        SQLException refused = assertThrows(SQLException.class,
                () -> trip("t-carry-2", "c-carry-2", "u-carry", "ASSIGNED"));
        assertEquals("23505", refused.getSQLState());
    }

    // ---- V3: people ----

    /** Age, sex and the "comes together" rules are enforced by the database too. */
    @Test
    void patientDetailsAreCheckedByTheDatabase() throws SQLException {
        String insert = "INSERT INTO patients (id, home, name, age, sex) VALUES (?, ST_Point(?, ?, 4326)::geography, ?, ?, ?)";
        update(conn, insert, "p-ok", DELHI_LON, DELHI_LAT, "Asha Rao", 68, "FEMALE");

        SQLException tooOld = assertThrows(SQLException.class,
                () -> update(conn, insert, "p-old", DELHI_LON, DELHI_LAT, "Old Rao", 150, "FEMALE"));
        SQLException badSex = assertThrows(SQLException.class,
                () -> update(conn, insert, "p-sex", DELHI_LON, DELHI_LAT, "Asha Rao", 68, "ROBOT"));
        SQLException nameOnly = assertThrows(SQLException.class, () -> update(conn,
                "INSERT INTO patients (id, home, name) VALUES (?, ST_Point(?, ?, 4326)::geography, ?)",
                "p-half", DELHI_LON, DELHI_LAT, "Asha Rao"));
        assertEquals("23514", tooOld.getSQLState());
        assertEquals("23514", badSex.getSQLState());
        assertEquals("23514", nameOnly.getSQLState());
    }

    /** A driver's name without a phone (or the other way round) is refused. */
    @Test
    void aDriverNeedsBothANameAndAPhone() throws SQLException {
        unit("u-driver", DELHI_LAT, DELHI_LON);

        assertEquals(1, update(conn, "UPDATE units SET driver_name = ?, driver_phone = ? WHERE id = ?",
                "Ravi Kumar", "+91 98450 12345", "u-driver"));
        SQLException refused = assertThrows(SQLException.class,
                () -> update(conn, "UPDATE units SET driver_phone = NULL WHERE id = ?", "u-driver"));
        assertEquals("23514", refused.getSQLState());
    }

    // ---- helpers ----

    /** @return a new connection to the test database */
    private static Connection connect() throws SQLException {
        return TestDatabase.dataSource().getConnection();
    }

    /**
     * Runs an INSERT/UPDATE/DELETE.
     *
     * @param c      the connection (and so the transaction) to run it on
     * @param sql    the statement, with {@code ?} for every value
     * @param params the values, in order
     * @return how many rows changed
     */
    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, params)) {
            return st.executeUpdate();
        }
    }

    /**
     * Runs a SELECT on the shared connection.
     *
     * @param sql    the query, with {@code ?} for every value
     * @param params the values, in order
     * @return the first column of every row, as text
     */
    private static List<String> strings(String sql, Object... params) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement st = prepare(conn, sql, params); ResultSet rows = st.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * Values go in through {@code ?} and setObject, sent separately from the SQL text and never pasted into it:
     * the habit that prevents SQL injection.
     */
    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            st.setObject(i + 1, params[i]);
        }
        return st;
    }

    /** A patient at home in central Delhi. ST_Point takes LONGITUDE first. */
    private static void patient(String id) throws SQLException {
        update(conn, "INSERT INTO patients (id, home) VALUES (?, ST_Point(?, ?, 4326)::geography)",
                id, DELHI_LON, DELHI_LAT);
    }

    /** An open (ALERTED) case. */
    private static void openCase(String id, String patientId) throws SQLException {
        update(conn, "INSERT INTO cases (id, patient_id, status, band, opened_at) VALUES (?, ?, 'ALERTED', 'HIGH', now())",
                id, patientId);
    }

    /** An available ambulance at (lat, lon); a new unit's moved_from is where it starts. */
    private static void unit(String id, double lat, double lon) throws SQLException {
        update(conn, "INSERT INTO units (id, type, status, position, moved_from) "
                        + "VALUES (?, 'AMBULANCE_ALS', 'AVAILABLE', ST_Point(?, ?, 4326)::geography, ST_Point(?, ?, 4326)::geography)",
                id, lon, lat, lon, lat);
    }

    /** A facility in central Delhi; {@code capabilities} is a Postgres array literal such as {@code "{STROKE}"}. */
    private static void facility(String id, String kind, String capabilities, boolean accepting) throws SQLException {
        update(conn, "INSERT INTO facilities (id, name, kind, location, capabilities, accepting) "
                        + "VALUES (?, ?, ?, ST_Point(?, ?, 4326)::geography, ?::text[], ?)",
                id, "Facility " + id, kind, DELHI_LON, DELHI_LAT, capabilities, accepting);
    }

    /** A PRIMARY trip in the given status. */
    private static void trip(String id, String caseId, String unitId, String status) throws SQLException {
        update(conn, "INSERT INTO assignments (id, case_id, unit_id, role, status, assigned_at) "
                        + "VALUES (?, ?, ?, 'PRIMARY', ?, now())",
                id, caseId, unitId, status);
    }
}

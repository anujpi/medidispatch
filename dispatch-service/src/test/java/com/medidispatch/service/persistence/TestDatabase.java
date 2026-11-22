package com.medidispatch.service.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * One real Postgres shared by every database test in the run (Testcontainers needs Docker running).
 *
 * <p>The container starts the first time a test asks for it and lives until the JVM ends (Testcontainers' cleanup
 * container, Ryuk, then removes it). Starting one container per test class would cost 5-10 s each.
 *
 * <p>It holds TWO databases, both migrated by Flyway at start:
 * <ul>
 *   <li>{@code test}: for the adapter tests (plain JDBC and the {@code @DataJpaTest} slice). Nothing runs in the
 *       background here; tests call {@link #reset()} in {@code @BeforeEach}.</li>
 *   <li>{@code app}: for tests that start the whole application ({@link TestDatabaseConfig}). Spring keeps started
 *       test contexts alive until the end of the run, and their scheduled dispatch loop keeps ticking every second;
 *       in the {@code test} database it would quietly poll the queue tests' cases. {@link #resetApp()} empties it.</li>
 * </ul>
 * Public (with {@link #resetApp()}, {@link #appUrlStarted()}, {@link #username()}, {@link #password()}) only so
 * whole-application tests in other packages can use the {@code app} database.
 */
public final class TestDatabase {

    /**
     * The same image as docker-compose.yml. Testcontainers' Postgres class only accepts images it knows;
     * {@code asCompatibleSubstituteFor} says "this one behaves like postgres".
     */
    private static final PostgreSQLContainer DB = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb-ha:pg17").asCompatibleSubstituteFor("postgres"));

    /** TRUNCATE takes every table at once, which satisfies the foreign keys; the schema stays. */
    private static final String TRUNCATE_ALL = "TRUNCATE vitals, assignments, units, cases, patients, facilities";

    private static DataSource dataSource;
    private static DataSource appDataSource;

    private TestDatabase() {
    }

    /**
     * Starts the container, migrates {@code test}, creates and migrates {@code app}, on the first call; later calls
     * return the same DataSource.
     *
     * @return a DataSource for the {@code test} database. {@link DriverManagerDataSource} opens a new connection on
     *         every request (no pool, auto-commit), which is all tests need, and lets concurrency tests get truly
     *         separate connections
     */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            DB.start();
            dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
            migrate(dataSource);
            // CREATE DATABASE cannot run inside a transaction; this connection is in auto-commit mode.
            JdbcClient.create(dataSource).sql("CREATE DATABASE app").update();
            appDataSource = new DriverManagerDataSource(appUrl(), DB.getUsername(), DB.getPassword());
            // Migrated here too, so resetApp() works before any Spring context has started; Spring's own Flyway
            // then finds V1 already applied.
            migrate(appDataSource);
        }
        return dataSource;
    }

    private static void migrate(DataSource target) {
        Flyway.configure().dataSource(target).load().migrate();
    }

    /**
     * @return a JdbcClient over the test database; each statement runs in its own transaction (auto-commit)
     */
    static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Empties every table of the {@code test} database. */
    static void reset() {
        jdbc().sql(TRUNCATE_ALL).update();
    }

    /** Empties every table of the {@code app} database (whole-application tests). */
    public static void resetApp() {
        dataSource();
        JdbcClient.create(appDataSource).sql(TRUNCATE_ALL).update();
    }

    /**
     * Called from inside {@link #dataSource()} too, so it must not call it (the container is already started there).
     *
     * @return the JDBC URL of the {@code app} database: same server and port as {@code test}, other database
     */
    static String appUrl() {
        return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/app";
    }

    /**
     * Inserts an open case (and its patient, {@code "p-" + caseId}) with plain SQL, for tests that need a case to
     * exist: {@code units.claimed_by} and {@code assignments.case_id} reference {@code cases}. Committed at once.
     *
     * @param caseId the case id
     */
    static void openCase(String caseId) {
        openCase(jdbc(), caseId);
    }

    /**
     * Same as {@link #openCase(String)}, through the given client: with a client over Spring's DataSource inside a
     * {@code @DataJpaTest}, the rows join the test's transaction and are rolled back with it.
     *
     * @param jdbc   the client to insert with
     * @param caseId the case id
     */
    static void openCase(JdbcClient jdbc, String caseId) {
        String patientId = "p-" + caseId;
        jdbc.sql("INSERT INTO patients (id, home) VALUES (:id, ST_Point(77.2090, 28.6139, 4326)::geography)")
                .param("id", patientId)
                .update();
        jdbc.sql("INSERT INTO cases (id, patient_id, status, band, opened_at) VALUES (:id, :patient, 'ALERTED', 'HIGH', now())")
                .param("id", caseId)
                .param("patient", patientId)
                .update();
    }

    // For TestDatabaseConfig: each first makes sure the container is started and migrated.

    /** @return the {@code app} database's JDBC URL, starting the container first if needed */
    public static String appUrlStarted() {
        dataSource();
        return appUrl();
    }

    /** @return the {@code test} database's JDBC URL (a random port on localhost) */
    static String url() {
        dataSource();
        return DB.getJdbcUrl();
    }

    /** @return the container's user name */
    public static String username() {
        dataSource();
        return DB.getUsername();
    }

    /** @return the container's password */
    public static String password() {
        dataSource();
        return DB.getPassword();
    }
}

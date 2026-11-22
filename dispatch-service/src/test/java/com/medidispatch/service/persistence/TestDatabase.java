package com.medidispatch.service.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * One real database shared by every database test in the run (Testcontainers needs Docker running).
 *
 * <p>The container starts the first time a test asks for it, Flyway applies the migrations once, and the
 * container lives until the JVM ends (Testcontainers' cleanup container, Ryuk, then removes it). Starting one
 * container per test class would cost 5-10 s each.
 *
 * <p>Tests call {@link #reset()} in {@code @BeforeEach}, so every test starts from empty tables whatever ran before.
 */
final class TestDatabase {

    /**
     * The same image as docker-compose.yml. Testcontainers' Postgres class only accepts images it knows;
     * {@code asCompatibleSubstituteFor} says "this one behaves like postgres".
     */
    private static final PostgreSQLContainer DB = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb-ha:pg17").asCompatibleSubstituteFor("postgres"));

    private static DataSource dataSource;

    private TestDatabase() {
    }

    /**
     * Starts the container and migrates it on the first call; later calls return the same DataSource.
     *
     * @return a DataSource for the test database. {@link DriverManagerDataSource} opens a new connection on every
     *         request (no pool), which is all tests need, and lets concurrency tests get truly separate connections
     */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            DB.start();
            dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
            Flyway.configure().dataSource(dataSource).load().migrate();
        }
        return dataSource;
    }

    /**
     * @return a JdbcClient over the test database; each statement runs in its own transaction (auto-commit)
     */
    static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Empties every table (the schema stays). Listing all of them at once satisfies the foreign keys. */
    static void reset() {
        jdbc().sql("TRUNCATE vitals, assignments, units, cases, patients").update();
    }

    /**
     * Inserts an open case (and its patient, {@code "p-" + caseId}) with plain SQL, for tests that need a case to
     * exist: {@code units.claimed_by} references {@code cases}.
     *
     * @param caseId the case id
     */
    static void openCase(String caseId) {
        String patientId = "p-" + caseId;
        jdbc().sql("INSERT INTO patients (id, home) VALUES (:id, ST_Point(77.2090, 28.6139, 4326)::geography)")
                .param("id", patientId)
                .update();
        jdbc().sql("INSERT INTO cases (id, patient_id, status, band, opened_at) VALUES (:id, :patient, 'ALERTED', 'HIGH', now())")
                .param("id", caseId)
                .param("patient", patientId)
                .update();
    }
}

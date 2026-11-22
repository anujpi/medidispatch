package com.medidispatch.service;

import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.service.persistence.TestDatabase;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import com.medidispatch.sim.HttpSimulation;
import com.medidispatch.sim.LiveConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end: the Bangalore simulator drives the REAL application over real HTTP, on a real Postgres.
 * <p>
 * {@code webEnvironment = RANDOM_PORT} starts the embedded web server on a free port ({@link LocalServerPort} says
 * which), so the simulator connects exactly like it would to a deployed service: readings, the live feed, the crews'
 * steps, all over the network stack.
 * <p>
 * A small city at the top speed, with no unit failures and many illnesses, so a case goes from the first reading to
 * CLOSED within seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabaseConfig.class)
class SimulatorOverHttpTest {

    /** 8 patients, 2 ALS, 2 BLS, 2 nurses, 1 doctor, 400 illnesses an hour, no failures, top speed. */
    private static final LiveConfig SMALL_FAST_CITY = new LiveConfig(42, 30, 8, 2, 2, 2, 1, 400, 0, 0, 0,
            Duration.ofMinutes(20), 300);

    private static final Duration WAIT = Duration.ofSeconds(90);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DispatchService service;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.resetApp();
    }

    @Test
    void aSimulatedPatientIsServedFromFirstReadingToClosedCaseOverHttp() throws InterruptedException {
        HttpSimulation city = new HttpSimulation(SMALL_FAST_CITY, URI.create("http://localhost:" + port));
        city.setUp();
        city.start();
        try {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (closedCases() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
            assertTrue(closedCases() > 0, "no case was closed within " + WAIT + ": " + city.status());
            assertTrue(city.readingsSent() > 0);
            assertTrue(city.tripsFinished() > 0);
            assertEquals(0, city.errors(), city.status());
        } finally {
            city.stop();
        }
        assertEquals(10, service.facilities().size());            // the six hospitals and four clinics
    }

    /**
     * Scene to hospital over HTTP: a crew takes a serious patient in, the service picks the hospital, the ambulance
     * drives there and hands over. The trip then holds its hospital and is DONE, and its case is CLOSED.
     */
    @Test
    void aSimulatedPatientIsTakenToHospitalAndHandedOver() throws InterruptedException {
        HttpSimulation city = new HttpSimulation(SMALL_FAST_CITY, URI.create("http://localhost:" + port));
        city.setUp();
        city.start();
        try {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (handedOver() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
            assertTrue(handedOver() > 0, "nobody was handed over at a hospital within " + WAIT + ": " + city.status());
            assertTrue(city.transported() > 0);
            assertEquals(0, city.errors(), city.status());
            String hospital = jdbc.sql("""
                    SELECT a.hospital_id FROM assignments a JOIN cases c ON c.id = a.case_id
                    WHERE a.hospital_id IS NOT NULL AND a.status = 'DONE' AND c.status = 'CLOSED' LIMIT 1
                    """).query(String.class).single();
            assertTrue(hospital.startsWith("blr-h-"), hospital);  // a hospital, never a clinic
        } finally {
            city.stop();
        }
    }

    /** A second city on top of a first would collide on ids, so set-up refuses a database that is not empty. */
    @Test
    void setUpRefusesADatabaseThatIsNotEmpty() {
        service.registerFacility(Facility.clinic("old-clinic", "Left Over", new Location(12.97, 77.59)));
        HttpSimulation city = new HttpSimulation(SMALL_FAST_CITY, URI.create("http://localhost:" + port));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> city.setUp());

        assertTrue(refused.getMessage().contains("docker compose down -v"), refused.getMessage());
        city.stop();
    }

    private long handedOver() {
        return jdbc.sql("SELECT count(*) FROM assignments WHERE hospital_id IS NOT NULL AND status = 'DONE'")
                .query(Long.class).single();
    }

    private long closedCases() {
        return jdbc.sql("SELECT count(*) FROM cases WHERE status = 'CLOSED'").query(Long.class).single();
    }
}

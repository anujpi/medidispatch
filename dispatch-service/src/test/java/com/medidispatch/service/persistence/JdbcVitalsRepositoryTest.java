package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.VitalsReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcVitalsRepository} on a real Postgres + TimescaleDB (the shared {@link TestDatabase}); mirrors
 * InMemoryVitalsRepositoryTest, plus: missing measurements stay missing, a repeated reading is ignored, and every
 * reading is kept. Patients p1 and p2 exist in every test (readings reference patients).
 */
class JdbcVitalsRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-22T12:00:00Z");

    private JdbcVitalsRepository repo;

    @BeforeEach
    void twoPatients() {
        TestDatabase.reset();
        JdbcPatientRepository patients = new JdbcPatientRepository(TestDatabase.jdbc());
        patients.save(new Patient("p1", new Location(28.6139, 77.2090)));
        patients.save(new Patient("p2", new Location(28.6139, 77.2090)));
        repo = new JdbcVitalsRepository(TestDatabase.jdbc());
    }

    @Test
    void aPatientWithNoReadingsHasNoLatest() {
        assertTrue(repo.latestFor("p1").isEmpty());
    }

    /** Records compare by value, so the reading read back equals the one saved: every field survived. */
    @Test
    void theOnlyReadingIsTheLatest() {
        VitalsReading r = reading("p1", T0);
        repo.save(r);

        assertEquals(r, repo.latestFor("p1").orElseThrow());
    }

    @Test
    void theLatestIsChosenByTimeNotBySaveOrder() {
        VitalsReading newer = reading("p1", T0.plusSeconds(60));
        VitalsReading older = reading("p1", T0);
        repo.save(newer);
        repo.save(older);

        assertEquals(newer, repo.latestFor("p1").orElseThrow());
    }

    @Test
    void patientsAreKeptApart() {
        VitalsReading forP1 = reading("p1", T0);
        VitalsReading forP2 = reading("p2", T0.plusSeconds(60));
        repo.save(forP1);
        repo.save(forP2);

        assertEquals(forP1, repo.latestFor("p1").orElseThrow());
        assertEquals(forP2, repo.latestFor("p2").orElseThrow());
        assertTrue(repo.latestFor("p3").isEmpty());
    }

    /** A wearable that measures only some things: NULL comes back as null, not 0. */
    @Test
    void missingMeasurementsStayMissing() {
        VitalsReading partial = new VitalsReading("p1", T0, null, 91, null, null, 22, null, Consciousness.CONFUSED);
        repo.save(partial);

        VitalsReading found = repo.latestFor("p1").orElseThrow();
        assertEquals(partial, found);
        assertNull(found.heartRate());
        assertNull(found.temperature());
    }

    /** The same patient and time again is ignored (ON CONFLICT DO NOTHING), not an error; the first one stays. */
    @Test
    void aRepeatedReadingIsIgnored() {
        VitalsReading first = reading("p1", T0);
        repo.save(first);

        repo.save(new VitalsReading("p1", T0, 130, 85, 90, 60, 30, 39.5, Consciousness.PAIN));

        assertEquals(first, repo.latestFor("p1").orElseThrow());
        assertEquals(List.of(1), countFor("p1"));
    }

    /** Unlike the in-memory version (latest only), every reading is kept: the history is the future training set. */
    @Test
    void everyReadingIsKept() {
        repo.save(reading("p1", T0));
        repo.save(reading("p1", T0.plusSeconds(60)));
        repo.save(reading("p1", T0.minusSeconds(60)));

        assertEquals(List.of(3), countFor("p1"));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.latestFor(null));
    }

    // ---- helpers ----

    private static VitalsReading reading(String patientId, Instant at) {
        return new VitalsReading(patientId, at, 75, 98, 120, 80, 14, 36.8, Consciousness.ALERT);
    }

    private static List<Integer> countFor(String patientId) {
        return TestDatabase.jdbc().sql("SELECT count(*) FROM vitals WHERE patient_id = :id")
                .param("id", patientId)
                .query(Integer.class)
                .list();
    }
}

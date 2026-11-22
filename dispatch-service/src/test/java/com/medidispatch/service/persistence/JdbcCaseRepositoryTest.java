package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcCaseRepository} on a real Postgres (the shared {@link TestDatabase}); mirrors InMemoryCaseRepositoryTest,
 * plus what the database adds. Patients p1-p3 exist in every test (cases reference patients).
 */
class JdbcCaseRepositoryTest {

    private static final Instant OPENED = Instant.parse("2026-11-22T12:00:00Z");

    private JdbcCaseRepository repo;

    @BeforeEach
    void threePatients() {
        TestDatabase.reset();
        JdbcPatientRepository patients = new JdbcPatientRepository(TestDatabase.jdbc());
        for (String id : List.of("p1", "p2", "p3")) {
            patients.save(new Patient(id, new Location(28.6139, 77.2090)));
        }
        repo = new JdbcCaseRepository(TestDatabase.jdbc());
    }

    // ---- save / findById ----

    @Test
    void savedCaseCanBeFoundById() {
        repo.save(newCase("c1", "p1"));

        assertEquals("p1", repo.findById("c1").orElseThrow().patientId());
    }

    /** Every field round-trips, including a status the constructor could never start in. */
    @Test
    void everyFieldComesBack() {
        repo.save(Case.restore("c1", "p1", CaseStatus.RESPONDING, RiskBand.CRITICAL, OPENED));

        Case found = repo.findById("c1").orElseThrow();
        assertEquals("c1", found.id());
        assertEquals("p1", found.patientId());
        assertEquals(CaseStatus.RESPONDING, found.status());
        assertEquals(RiskBand.CRITICAL, found.band());
        assertEquals(OPENED, found.openedAt());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameIdTwiceReplacesTheFirst() {
        repo.save(new Case("c1", "p1", RiskBand.LOW, OPENED));
        repo.save(new Case("c1", "p1", RiskBand.CRITICAL, OPENED));

        assertEquals(RiskBand.CRITICAL, repo.findById("c1").orElseThrow().band());
    }

    // ---- open case lookup ----

    @Test
    void anOpenCaseIsFoundByPatientId() {
        repo.save(newCase("c1", "p1"));

        assertEquals("c1", repo.findOpenByPatientId("p1").orElseThrow().id());
    }

    @Test
    void aPatientWithNoCasesHasNoOpenCase() {
        repo.save(newCase("c1", "p1"));

        assertTrue(repo.findOpenByPatientId("p2").isEmpty());
    }

    @Test
    void aClosedCaseIsNotOpen() {
        Case c = newCase("c1", "p1");
        close(c);
        repo.save(c);

        assertTrue(repo.findOpenByPatientId("p1").isEmpty());
    }

    @Test
    void aCancelledCaseIsNotOpen() {
        Case c = newCase("c1", "p1");
        c.transitionTo(CaseStatus.CANCELLED);
        repo.save(c);

        assertTrue(repo.findOpenByPatientId("p1").isEmpty());
    }

    @Test
    void theOpenCaseIsFoundEvenIfAnOlderOneIsClosed() {
        Case old = newCase("old", "p1");
        close(old);
        repo.save(old);
        repo.save(newCase("current", "p1"));

        assertEquals("current", repo.findOpenByPatientId("p1").orElseThrow().id());
    }

    @Test
    void otherPatientsCasesAreNotMixedUp() {
        repo.save(newCase("c1", "p1"));
        repo.save(newCase("c2", "p2"));

        assertEquals("c1", repo.findOpenByPatientId("p1").orElseThrow().id());
        assertEquals("c2", repo.findOpenByPatientId("p2").orElseThrow().id());
    }

    /** New with the database: the partial unique index refuses a second open case for one patient. */
    @Test
    void aSecondOpenCaseForOnePatientIsRefused() {
        repo.save(newCase("c1", "p1"));

        assertThrows(DuplicateKeyException.class, () -> repo.save(newCase("c2", "p1")));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findOpenByPatientId(null));
    }

    // ---- findOpen ----

    @Test
    void findOpenReturnsOnlyOpenCasesOldestFirst() {
        repo.save(new Case("late", "p1", RiskBand.HIGH, OPENED.plusSeconds(60)));
        repo.save(new Case("early", "p2", RiskBand.LOW, OPENED));
        Case done = newCase("done", "p3");
        close(done);
        repo.save(done);

        assertEquals(List.of("early", "late"), ids(repo.findOpen()));
    }

    @Test
    void findOpenBreaksTiesById() {
        repo.save(new Case("b", "p1", RiskBand.HIGH, OPENED));
        repo.save(new Case("a", "p2", RiskBand.HIGH, OPENED));

        assertEquals(List.of("a", "b"), ids(repo.findOpen()));
    }

    /**
     * The opposite of the in-memory quirk (there, closing the stored object without saving dropped it from
     * findOpen): here only what was SAVED counts. The engine always saves after a change, so it sees no difference.
     */
    @Test
    void aChangeThatWasNotSavedIsNotSeen() {
        Case c = newCase("c1", "p1");
        repo.save(c);
        close(c);

        assertEquals(List.of("c1"), ids(repo.findOpen()));
    }

    // ---- helpers ----

    /** A new case for the patient (MONITORING, HIGH): open. */
    private static Case newCase(String id, String patientId) {
        return new Case(id, patientId, RiskBand.HIGH, OPENED);
    }

    private static void close(Case c) {
        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);
    }

    private static List<String> ids(List<Case> cases) {
        List<String> ids = new ArrayList<>();
        for (Case c : cases) {
            ids.add(c.id());
        }
        return ids;
    }
}

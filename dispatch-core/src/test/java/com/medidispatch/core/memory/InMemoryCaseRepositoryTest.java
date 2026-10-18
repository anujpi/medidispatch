package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryCaseRepositoryTest {

    private static final Instant OPENED = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryCaseRepository repo = new InMemoryCaseRepository();

    private Case newCase(String id, String patientId) {
        return new Case(id, patientId, RiskBand.HIGH, OPENED);
    }

    private void close(Case c) {
        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);
    }

    // ---------- save / findById ----------

    @Test
    void savedCaseCanBeFoundById() {
        repo.save(newCase("c1", "p1"));

        assertEquals("p1", repo.findById("c1").get().patientId());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameIdTwiceReplacesTheFirst() {
        repo.save(new Case("c1", "p1", RiskBand.LOW, OPENED));
        repo.save(new Case("c1", "p1", RiskBand.CRITICAL, OPENED));

        assertEquals(RiskBand.CRITICAL, repo.findById("c1").get().band());
    }

    // ---------- open case lookup ----------

    @Test
    void anOpenCaseIsFoundByPatientId() {
        repo.save(newCase("c1", "p1"));

        assertEquals("c1", repo.findOpenByPatientId("p1").get().id());
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

        assertEquals("current", repo.findOpenByPatientId("p1").get().id());
    }

    @Test
    void otherPatientsCasesAreNotMixedUp() {
        repo.save(newCase("c1", "p1"));
        repo.save(newCase("c2", "p2"));

        assertEquals("c1", repo.findOpenByPatientId("p1").get().id());
        assertEquals("c2", repo.findOpenByPatientId("p2").get().id());
    }

    // ---------- validation ----------

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findOpenByPatientId(null));
    }
}
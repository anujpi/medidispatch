package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class CaseTest {

    private static final Instant OPENED = Instant.parse("2026-10-07T12:00:00Z");

    private Case newCase() {
        return new Case("c1", "p1", RiskBand.HIGH, OPENED);
    }

    // ---------- construction ----------

    @Test
    void newCaseStartsMonitoringWithTheGivenValues() {
        Case c = newCase();

        assertEquals("c1", c.id());
        assertEquals("p1", c.patientId());
        assertEquals(RiskBand.HIGH, c.band());
        assertEquals(OPENED, c.openedAt());
        assertEquals(CaseStatus.MONITORING, c.status());
    }

    @Test
    void nullOrBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Case(null, "p1", RiskBand.LOW, OPENED));
        assertThrows(IllegalArgumentException.class, () -> new Case("  ", "p1", RiskBand.LOW, OPENED));
    }

    @Test
    void nullOrBlankPatientIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Case("c1", null, RiskBand.LOW, OPENED));
        assertThrows(IllegalArgumentException.class, () -> new Case("c1", " ", RiskBand.LOW, OPENED));
    }

    @Test
    void nullBandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Case("c1", "p1", null, OPENED));
    }

    @Test
    void nullOpenedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Case("c1", "p1", RiskBand.LOW, null));
    }

    // ---------- transitions ----------

    @Test
    void legalTransitionChangesTheStatus() {
        Case c = newCase();

        c.transitionTo(CaseStatus.ALERTED);

        assertEquals(CaseStatus.ALERTED, c.status());
    }

    @Test
    void fullHappyPathReachesClosed() {
        Case c = newCase();

        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);

        assertEquals(CaseStatus.CLOSED, c.status());
    }

    @Test
    void backEdgesWork() {
        Case c = newCase();
        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.MONITORING);
        assertEquals(CaseStatus.MONITORING, c.status());

        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.ALERTED);               // relapse
        assertEquals(CaseStatus.ALERTED, c.status());
    }

    @Test
    void illegalTransitionThrowsAndLeavesTheStatusUnchanged() {
        Case c = newCase();                               // MONITORING

        assertThrows(IllegalStateException.class, () -> c.transitionTo(CaseStatus.STABILISED));

        assertEquals(CaseStatus.MONITORING, c.status());
    }

    @Test
    void errorMessageNamesBothStatuses() {
        Case c = newCase();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> c.transitionTo(CaseStatus.CLOSED));

        assertTrue(e.getMessage().contains("MONITORING"));
        assertTrue(e.getMessage().contains("CLOSED"));
    }

    @Test
    void nullNextStatusIsRejected() {
        Case c = newCase();

        assertThrows(IllegalArgumentException.class, () -> c.transitionTo(null));
        assertEquals(CaseStatus.MONITORING, c.status());
    }

    @Test
    void nothingLeavesClosed() {
        Case c = newCase();
        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);

        assertThrows(IllegalStateException.class, () -> c.transitionTo(CaseStatus.ALERTED));
        assertEquals(CaseStatus.CLOSED, c.status());
    }

    @Test
    void nothingLeavesCancelled() {
        Case c = newCase();
        c.transitionTo(CaseStatus.CANCELLED);

        assertThrows(IllegalStateException.class, () -> c.transitionTo(CaseStatus.MONITORING));
        assertEquals(CaseStatus.CANCELLED, c.status());
    }

    // ---------- band ----------

    @Test
    void bandCanBeUpdatedOnAnOpenCase() {
        Case c = newCase();

        c.updateBand(RiskBand.CRITICAL);

        assertEquals(RiskBand.CRITICAL, c.band());
    }

    @Test
    void nullBandUpdateIsRejected() {
        Case c = newCase();

        assertThrows(IllegalArgumentException.class, () -> c.updateBand(null));
        assertEquals(RiskBand.HIGH, c.band());
    }

    @Test
    void bandCannotBeUpdatedOnAClosedCase() {
        Case c = newCase();
        c.transitionTo(CaseStatus.ALERTED);
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);

        assertThrows(IllegalStateException.class, () -> c.updateBand(RiskBand.LOW));
        assertEquals(RiskBand.HIGH, c.band());
    }

    @Test
    void bandCannotBeUpdatedOnACancelledCase() {
        Case c = newCase();
        c.transitionTo(CaseStatus.CANCELLED);

        assertThrows(IllegalStateException.class, () -> c.updateBand(RiskBand.LOW));
    }
}
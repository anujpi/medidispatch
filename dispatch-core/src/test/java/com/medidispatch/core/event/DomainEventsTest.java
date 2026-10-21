package com.medidispatch.core.event;

import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class DomainEventsTest {

    private static final Instant T = Instant.parse("2026-10-07T12:00:00Z");

    // ---------- valid events ----------

    @Test
    void caseStatusChangedKeepsItsValues() {
        CaseStatusChanged e = new CaseStatusChanged("c1", "p1", CaseStatus.MONITORING, CaseStatus.ALERTED, T);

        assertEquals("c1", e.caseId());
        assertEquals("p1", e.patientId());
        assertEquals(CaseStatus.MONITORING, e.from());
        assertEquals(CaseStatus.ALERTED, e.to());
        assertEquals(T, e.occurredAt());
    }

    @Test
    void caseBandChangedKeepsItsValues() {
        CaseBandChanged e = new CaseBandChanged("c1", "p1", RiskBand.HIGH, RiskBand.CRITICAL, T);

        assertEquals(RiskBand.HIGH, e.from());
        assertEquals(RiskBand.CRITICAL, e.to());
        assertEquals(T, e.occurredAt());
    }

    @Test
    void unitAssignedKeepsItsValues() {
        UnitAssigned e = new UnitAssigned("a1", "c1", "r1", T);

        assertEquals("a1", e.assignmentId());
        assertEquals("c1", e.caseId());
        assertEquals("r1", e.resourceId());
        assertEquals(T, e.occurredAt());
    }

    @Test
    void assignmentStatusChangedKeepsItsValues() {
        AssignmentStatusChanged e = new AssignmentStatusChanged(
                "a1", "c1", "r1", AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, T);

        assertEquals(AssignmentStatus.ASSIGNED, e.from());
        assertEquals(AssignmentStatus.EN_ROUTE, e.to());
        assertEquals(T, e.occurredAt());
    }

    @Test
    void equalEventsAreEqual() {                                   // records compare by value
        assertEquals(new UnitAssigned("a1", "c1", "r1", T), new UnitAssigned("a1", "c1", "r1", T));
        assertNotEquals(new UnitAssigned("a1", "c1", "r1", T), new UnitAssigned("a2", "c1", "r1", T));
    }

    @Test
    void everyEventIsADomainEvent() {
        DomainEvent e = new UnitAssigned("a1", "c1", "r1", T);

        assertEquals(T, e.occurredAt());
    }

    // ---------- validation ----------

    @Test
    void caseStatusChangedRejectsBadValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaseStatusChanged(null, "p1", CaseStatus.MONITORING, CaseStatus.ALERTED, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseStatusChanged("c1", " ", CaseStatus.MONITORING, CaseStatus.ALERTED, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseStatusChanged("c1", "p1", null, CaseStatus.ALERTED, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseStatusChanged("c1", "p1", CaseStatus.MONITORING, null, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseStatusChanged("c1", "p1", CaseStatus.MONITORING, CaseStatus.ALERTED, null));
    }

    @Test
    void caseBandChangedRejectsBadValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaseBandChanged("", "p1", RiskBand.LOW, RiskBand.HIGH, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseBandChanged("c1", null, RiskBand.LOW, RiskBand.HIGH, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseBandChanged("c1", "p1", null, RiskBand.HIGH, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseBandChanged("c1", "p1", RiskBand.LOW, null, T));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseBandChanged("c1", "p1", RiskBand.LOW, RiskBand.HIGH, null));
    }

    @Test
    void unitAssignedRejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new UnitAssigned(null, "c1", "r1", T));
        assertThrows(IllegalArgumentException.class, () -> new UnitAssigned("a1", " ", "r1", T));
        assertThrows(IllegalArgumentException.class, () -> new UnitAssigned("a1", "c1", null, T));
        assertThrows(IllegalArgumentException.class, () -> new UnitAssigned("a1", "c1", "r1", null));
    }

    @Test
    void assignmentStatusChangedRejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                null, "c1", "r1", AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, T));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                "a1", "", "r1", AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, T));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                "a1", "c1", null, AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, T));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                "a1", "c1", "r1", null, AssignmentStatus.EN_ROUTE, T));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                "a1", "c1", "r1", AssignmentStatus.ASSIGNED, null, T));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatusChanged(
                "a1", "c1", "r1", AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, null));
    }
}
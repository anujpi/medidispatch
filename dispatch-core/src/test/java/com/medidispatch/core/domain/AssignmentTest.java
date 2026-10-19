package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class AssignmentTest {

    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-07T12:00:00Z");

    private Assignment newAssignment() {
        return new Assignment("case-1", "r1", ASSIGNED_AT);
    }

    // ---------- construction ----------

    @Test
    void newAssignmentIsAssignedWithTheGivenValues() {
        Assignment a = newAssignment();

        assertEquals("case-1", a.caseId());
        assertEquals("r1", a.resourceId());
        assertEquals(ASSIGNED_AT, a.assignedAt());
        assertEquals(AssignmentStatus.ASSIGNED, a.status());
    }

    @Test
    void everyAssignmentGetsItsOwnId() {
        Assignment a = newAssignment();
        Assignment b = newAssignment();

        assertFalse(a.id().isBlank());
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void nullOrBlankCaseIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment(null, "r1", ASSIGNED_AT));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("  ", "r1", ASSIGNED_AT));
    }

    @Test
    void nullOrBlankResourceIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", null, ASSIGNED_AT));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", " ", ASSIGNED_AT));
    }

    @Test
    void nullAssignedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", "r1", null));
    }

    // ---------- transitions ----------

    @Test
    void fullHappyPathReachesDone() {
        Assignment a = newAssignment();

        a.transitionTo(AssignmentStatus.EN_ROUTE);
        a.transitionTo(AssignmentStatus.ARRIVED);
        a.transitionTo(AssignmentStatus.DONE);

        assertEquals(AssignmentStatus.DONE, a.status());
    }

    @Test
    void canBeCancelledFromEveryNonTerminalStatus() {
        Assignment fromAssigned = newAssignment();
        fromAssigned.transitionTo(AssignmentStatus.CANCELLED);
        assertEquals(AssignmentStatus.CANCELLED, fromAssigned.status());

        Assignment fromEnRoute = newAssignment();
        fromEnRoute.transitionTo(AssignmentStatus.EN_ROUTE);
        fromEnRoute.transitionTo(AssignmentStatus.CANCELLED);
        assertEquals(AssignmentStatus.CANCELLED, fromEnRoute.status());

        Assignment fromArrived = newAssignment();
        fromArrived.transitionTo(AssignmentStatus.EN_ROUTE);
        fromArrived.transitionTo(AssignmentStatus.ARRIVED);
        fromArrived.transitionTo(AssignmentStatus.CANCELLED);
        assertEquals(AssignmentStatus.CANCELLED, fromArrived.status());
    }

    @Test
    void illegalTransitionThrowsAndLeavesTheStatusUnchanged() {
        Assignment a = newAssignment();                       // ASSIGNED

        assertThrows(IllegalStateException.class, () -> a.transitionTo(AssignmentStatus.ARRIVED));

        assertEquals(AssignmentStatus.ASSIGNED, a.status());
    }

    @Test
    void errorMessageNamesBothStatuses() {
        Assignment a = newAssignment();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> a.transitionTo(AssignmentStatus.DONE));

        assertTrue(e.getMessage().contains("ASSIGNED"));
        assertTrue(e.getMessage().contains("DONE"));
    }

    @Test
    void nullNextStatusIsRejectedAndStatusUnchanged() {
        Assignment a = newAssignment();

        assertThrows(IllegalArgumentException.class, () -> a.transitionTo(null));

        assertEquals(AssignmentStatus.ASSIGNED, a.status());
    }

    @Test
    void nothingLeavesDone() {
        Assignment a = newAssignment();
        a.transitionTo(AssignmentStatus.EN_ROUTE);
        a.transitionTo(AssignmentStatus.ARRIVED);
        a.transitionTo(AssignmentStatus.DONE);

        assertThrows(IllegalStateException.class, () -> a.transitionTo(AssignmentStatus.CANCELLED));
        assertEquals(AssignmentStatus.DONE, a.status());
    }

    @Test
    void nothingLeavesCancelled() {
        Assignment a = newAssignment();
        a.transitionTo(AssignmentStatus.CANCELLED);

        assertThrows(IllegalStateException.class, () -> a.transitionTo(AssignmentStatus.EN_ROUTE));
        assertEquals(AssignmentStatus.CANCELLED, a.status());
    }
}
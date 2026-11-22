package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AssignmentTest {

    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-07T12:00:00Z");

    private Assignment newAssignment() {
        return new Assignment("case-1", "r1", AssignmentRole.PRIMARY, ASSIGNED_AT);
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
        assertThrows(IllegalArgumentException.class, () -> new Assignment(null, "r1", AssignmentRole.PRIMARY, ASSIGNED_AT));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("  ", "r1", AssignmentRole.PRIMARY, ASSIGNED_AT));
    }

    @Test
    void nullOrBlankResourceIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", null, AssignmentRole.PRIMARY, ASSIGNED_AT));
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", " ", AssignmentRole.PRIMARY, ASSIGNED_AT));
    }

    @Test
    void nullAssignedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", "r1", AssignmentRole.PRIMARY, null));
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

    // ---------- role ----------

    @Test
    void theRoleIsKept() {
        assertEquals(AssignmentRole.PRIMARY, newAssignment().role());
        assertEquals(AssignmentRole.SUPPORT,
                new Assignment("case-1", "nurse-1", AssignmentRole.SUPPORT, ASSIGNED_AT).role());
    }

    @Test
    void aMissingRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Assignment("case-1", "r1", null, ASSIGNED_AT));
    }

    // ---------- demotion to first responder ----------

    @Test
    void aMainResponseCanBecomeAFirstResponderAndKeepsGoing() {
        Assignment a = newAssignment();
        a.transitionTo(AssignmentStatus.EN_ROUTE);

        a.demoteToSupport();

        assertEquals(AssignmentRole.SUPPORT, a.role());
        assertEquals(AssignmentStatus.EN_ROUTE, a.status());
    }

    @Test
    void aFirstResponderCannotBeDemotedAgain() {
        Assignment a = newAssignment();
        a.demoteToSupport();

        assertThrows(IllegalStateException.class, () -> a.demoteToSupport());
        assertEquals(AssignmentRole.SUPPORT, a.role());
    }

    @Test
    void anEndedTripCannotBeDemotedAndNothingChanges() {
        Assignment a = newAssignment();
        a.transitionTo(AssignmentStatus.CANCELLED);

        assertThrows(IllegalStateException.class, () -> a.demoteToSupport());
        assertEquals(AssignmentRole.PRIMARY, a.role());
    }

    // ---- restore: rebuilding a stored trip (used by storage adapters) ----

    /** restore keeps the saved id (no new random one), role and status. */
    @Test
    void restoreRebuildsEveryField() {
        Assignment a = Assignment.restore("t1", "c1", "u1", AssignmentRole.SUPPORT, AssignmentStatus.EN_ROUTE, ASSIGNED_AT, null, Set.of());

        assertEquals("t1", a.id());
        assertEquals("c1", a.caseId());
        assertEquals("u1", a.resourceId());
        assertEquals(AssignmentRole.SUPPORT, a.role());
        assertEquals(AssignmentStatus.EN_ROUTE, a.status());
        assertEquals(ASSIGNED_AT, a.assignedAt());
    }

    /** A restored trip still obeys its rules: a finished trip cannot restart or be demoted. */
    @Test
    void aRestoredTripStillObeysTheRules() {
        Assignment a = Assignment.restore("t1", "c1", "u1", AssignmentRole.PRIMARY, AssignmentStatus.DONE, ASSIGNED_AT, null, Set.of());

        assertThrows(IllegalStateException.class, () -> a.transitionTo(AssignmentStatus.EN_ROUTE));
        assertThrows(IllegalStateException.class, () -> a.demoteToSupport());
        assertEquals(AssignmentStatus.DONE, a.status());
        assertEquals(AssignmentRole.PRIMARY, a.role());
    }

    /** restore checks its arguments like the constructor, plus the id and the status. */
    @Test
    void restoreRejectsMissingValues() {
        assertThrows(IllegalArgumentException.class,
                () -> Assignment.restore(" ", "c1", "u1", AssignmentRole.PRIMARY, AssignmentStatus.ASSIGNED, ASSIGNED_AT, null, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Assignment.restore("t1", "c1", "u1", AssignmentRole.PRIMARY, null, ASSIGNED_AT, null, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Assignment.restore("t1", "c1", "u1", null, AssignmentStatus.ASSIGNED, ASSIGNED_AT, null, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Assignment.restore("t1", "", "u1", AssignmentRole.PRIMARY, AssignmentStatus.ASSIGNED, ASSIGNED_AT, null, Set.of()));
    }

    // ---- transport: the second leg, scene to hospital ----

    private Assignment arrived(AssignmentRole role) {
        Assignment a = new Assignment("c1", "u1", role, ASSIGNED_AT);
        a.transitionTo(AssignmentStatus.EN_ROUTE);
        a.transitionTo(AssignmentStatus.ARRIVED);
        return a;
    }

    @Test
    void aNewTripHasNoHospital() {
        assertTrue(newAssignment().hospitalId().isEmpty());
    }

    @Test
    void startTransportMovesToTransportingAndRecordsTheHospitalAndNeeds() {
        Assignment a = arrived(AssignmentRole.PRIMARY);

        a.startTransport("h-apollo", Set.of(Capability.STROKE));

        assertEquals(AssignmentStatus.TRANSPORTING, a.status());
        assertEquals("h-apollo", a.hospitalId().orElseThrow());
        assertEquals(Set.of(Capability.STROKE), a.needs());
    }

    @Test
    void aNewTripNeedsNothing() {
        assertTrue(newAssignment().needs().isEmpty());
    }

    /** The needs are copied: changing the caller's set later changes nothing, and the copy cannot be changed. */
    @Test
    void needsAreAnUnmodifiableCopy() {
        Assignment a = arrived(AssignmentRole.PRIMARY);
        Set<Capability> given = new HashSet<>(Set.of(Capability.TRAUMA));

        a.startTransport("h-apollo", given);
        given.add(Capability.PAEDIATRIC);

        assertEquals(Set.of(Capability.TRAUMA), a.needs());
        assertThrows(UnsupportedOperationException.class, () -> a.needs().add(Capability.CARDIAC));
    }

    @Test
    void missingOrNullNeedsAreRejectedAndNothingChanges() {
        Assignment a = arrived(AssignmentRole.PRIMARY);
        Set<Capability> withNull = new HashSet<>();
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> a.startTransport("h-apollo", null));
        assertThrows(IllegalArgumentException.class, () -> a.startTransport("h-apollo", withNull));
        assertEquals(AssignmentStatus.ARRIVED, a.status());
        assertTrue(a.hospitalId().isEmpty());
    }

    /** The hospital stays on the trip after it ends: the record says where the patient went. */
    @Test
    void theHospitalIsKeptWhenTheTripIsDone() {
        Assignment a = arrived(AssignmentRole.PRIMARY);
        a.startTransport("h-apollo", Set.of());

        a.transitionTo(AssignmentStatus.DONE);

        assertEquals("h-apollo", a.hospitalId().orElseThrow());
    }

    @Test
    void aFirstResponderCannotTransport() {
        Assignment a = arrived(AssignmentRole.SUPPORT);

        assertThrows(IllegalStateException.class, () -> a.startTransport("h-apollo", Set.of()));
        assertEquals(AssignmentStatus.ARRIVED, a.status());
        assertTrue(a.hospitalId().isEmpty());
    }

    @Test
    void onlyAnArrivedTripCanTransport() {
        Assignment a = newAssignment();

        assertThrows(IllegalStateException.class, () -> a.startTransport("h-apollo", Set.of()));
        assertEquals(AssignmentStatus.ASSIGNED, a.status());
        assertTrue(a.hospitalId().isEmpty());
    }

    @Test
    void aBlankHospitalIsRejected() {
        Assignment a = arrived(AssignmentRole.PRIMARY);

        assertThrows(IllegalArgumentException.class, () -> a.startTransport(" ", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> a.startTransport(null, Set.of()));
        assertEquals(AssignmentStatus.ARRIVED, a.status());
    }

    /** transitionTo cannot reach TRANSPORTING: that would be a transport with no destination. */
    @Test
    void transitionToCannotStartATransport() {
        Assignment a = arrived(AssignmentRole.PRIMARY);

        assertThrows(IllegalStateException.class, () -> a.transitionTo(AssignmentStatus.TRANSPORTING));
        assertEquals(AssignmentStatus.ARRIVED, a.status());
    }

    @Test
    void aTransportingTripCannotBeDemoted() {
        Assignment a = arrived(AssignmentRole.PRIMARY);
        a.startTransport("h-apollo", Set.of());

        assertThrows(IllegalStateException.class, () -> a.demoteToSupport());
        assertEquals(AssignmentRole.PRIMARY, a.role());
    }

    @Test
    void restoreKeepsTheHospitalAndNeeds() {
        Assignment a = Assignment.restore("t1", "c1", "u1", AssignmentRole.PRIMARY, AssignmentStatus.TRANSPORTING,
                ASSIGNED_AT, "h-apollo", Set.of(Capability.STROKE));

        assertEquals(AssignmentStatus.TRANSPORTING, a.status());
        assertEquals("h-apollo", a.hospitalId().orElseThrow());
        assertEquals(Set.of(Capability.STROKE), a.needs());
    }

    @Test
    void restoreRejectsMissingNeeds() {
        assertThrows(IllegalArgumentException.class, () -> Assignment.restore("t1", "c1", "u1",
                AssignmentRole.PRIMARY, AssignmentStatus.DONE, ASSIGNED_AT, null, null));
    }

    @Test
    void restoreRejectsATransportWithoutAHospitalOrABlankHospital() {
        assertThrows(IllegalArgumentException.class, () -> Assignment.restore("t1", "c1", "u1",
                AssignmentRole.PRIMARY, AssignmentStatus.TRANSPORTING, ASSIGNED_AT, null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> Assignment.restore("t1", "c1", "u1",
                AssignmentRole.PRIMARY, AssignmentStatus.DONE, ASSIGNED_AT, " ", Set.of()));
    }

    /** The public constructor is unchanged: a new random id each time, starting in ASSIGNED. */
    @Test
    void aNewTripStillGetsItsOwnIdAndStartsAssigned() {
        Assignment a = new Assignment("c1", "u1", AssignmentRole.PRIMARY, ASSIGNED_AT);
        Assignment b = new Assignment("c1", "u1", AssignmentRole.PRIMARY, ASSIGNED_AT);

        assertNotEquals(a.id(), b.id());
        assertEquals(AssignmentStatus.ASSIGNED, a.status());
    }
}

package com.medidispatch.core.memory;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryAssignmentRepositoryTest {

    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryAssignmentRepository repo = new InMemoryAssignmentRepository();

    private Assignment assignment(String caseId, String resourceId) {
        return new Assignment(caseId, resourceId, AssignmentRole.PRIMARY, ASSIGNED_AT);
    }

    private void finish(Assignment a) {
        a.transitionTo(AssignmentStatus.EN_ROUTE);
        a.transitionTo(AssignmentStatus.ARRIVED);
        a.transitionTo(AssignmentStatus.DONE);
    }

    // ---------- save / findById ----------

    @Test
    void savedAssignmentCanBeFoundById() {
        Assignment a = assignment("case-1", "r1");
        repo.save(a);

        Assignment found = repo.findById(a.id()).get();

        assertEquals("case-1", found.caseId());
        assertEquals("r1", found.resourceId());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameAssignmentTwiceKeepsOneEntry() {
        Assignment a = assignment("case-1", "r1");
        repo.save(a);
        repo.save(a);

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get(0).id());
    }

    @Test
    void aStatusChangeIsSeenAfterSavingAgain() {
        Assignment a = assignment("case-1", "r1");
        repo.save(a);

        a.transitionTo(AssignmentStatus.EN_ROUTE);
        repo.save(a);                                       // always save after changing

        assertEquals(AssignmentStatus.EN_ROUTE, repo.findById(a.id()).get().status());
    }

    // ---------- active lookup by case ----------

    @Test
    void anActiveAssignmentIsFoundByCaseId() {
        Assignment a = assignment("case-1", "r1");
        repo.save(a);

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get(0).id());
    }

    @Test
    void aCaseWithNoAssignmentHasNoActiveOne() {
        repo.save(assignment("case-1", "r1"));

        assertTrue(repo.findActiveByCaseId("case-2").isEmpty());
    }

    @Test
    void aDoneAssignmentIsNotActive() {
        Assignment a = assignment("case-1", "r1");
        finish(a);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aCancelledAssignmentIsNotActive() {
        Assignment a = assignment("case-1", "r1");
        a.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void theLiveAssignmentIsFoundEvenIfAnOlderOneWasCancelled() {   // what a reassignment looks like
        Assignment old = assignment("case-1", "r1");
        old.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(old);
        Assignment current = assignment("case-1", "r2");
        repo.save(current);

        Assignment found = repo.findActiveByCaseId("case-1").get(0);

        assertEquals(current.id(), found.id());
        assertEquals("r2", found.resourceId());
    }

    @Test
    void otherCasesAssignmentsAreNotMixedUp() {
        Assignment a = assignment("case-1", "r1");
        Assignment b = assignment("case-2", "r2");
        repo.save(a);
        repo.save(b);

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get(0).id());
        assertEquals(b.id(), repo.findActiveByCaseId("case-2").get(0).id());
    }

    @Test
    void aCaseCanHaveSeveralActiveTripsAtOnce() {                   // an ambulance and a first responder
        Assignment ambulance = assignment("case-1", "als-1");
        Assignment nurse = assignment("case-1", "nurse-1");
        Assignment finished = assignment("case-1", "bls-1");
        finish(finished);
        repo.save(ambulance);
        repo.save(nurse);
        repo.save(finished);

        List<Assignment> active = repo.findActiveByCaseId("case-1");

        assertEquals(2, active.size());
        assertTrue(active.contains(ambulance));
        assertTrue(active.contains(nurse));
    }

    // ---------- active lookup by unit ----------

    @Test
    void aUnitsActiveTripIsFoundByItsId() {
        Assignment a = assignment("case-1", "r1");
        repo.save(a);

        assertEquals(a.id(), repo.findActiveByResourceId("r1").get().id());
    }

    @Test
    void aUnitWhoseTripsHaveAllEndedHasNoActiveTrip() {
        Assignment old = assignment("case-1", "r1");
        finish(old);
        repo.save(old);

        assertTrue(repo.findActiveByResourceId("r1").isEmpty());
        assertTrue(repo.findActiveByResourceId("never-used").isEmpty());
    }

    @Test
    void theUnitsCurrentTripIsFoundEvenIfAnOlderOneEnded() {
        Assignment old = assignment("case-1", "r1");
        old.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(old);
        Assignment current = assignment("case-2", "r1");
        repo.save(current);

        assertEquals(current.id(), repo.findActiveByResourceId("r1").get().id());
    }

    // ---------- validation ----------

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findActiveByCaseId(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findActiveByResourceId(null));
    }
    // ---------- findActive ----------

    @Test
    void findActiveIsEmptyWhenNothingIsStored() {
        assertTrue(repo.findActive().isEmpty());
    }

    @Test
    void findActiveReturnsOnlyAssignmentsThatAreNotFinished() {
        Assignment live = assignment("case-1", "r1");
        Assignment done = assignment("case-2", "r2");
        Assignment cancelled = assignment("case-3", "r3");
        Assignment timedOut = assignment("case-4", "r4");
        finish(done);
        cancelled.transitionTo(AssignmentStatus.CANCELLED);
        timedOut.transitionTo(AssignmentStatus.TIMED_OUT);
        repo.save(live);
        repo.save(done);
        repo.save(cancelled);
        repo.save(timedOut);

        assertEquals(1, repo.findActive().size());
        assertEquals(live.id(), repo.findActive().get(0).id());
    }

    @Test
    void findActiveReturnsACopyTheCallerCanChange() {
        repo.save(assignment("case-1", "r1"));

        repo.findActive().clear();

        assertEquals(1, repo.findActive().size());
    }

    @Test
    void aTimedOutAssignmentIsNotActiveForItsCase() {
        Assignment a = assignment("case-1", "r1");
        a.transitionTo(AssignmentStatus.TIMED_OUT);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("case-1").isEmpty());
    }
}
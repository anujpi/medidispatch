package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryAssignmentRepositoryTest {

    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryAssignmentRepository repo = new InMemoryAssignmentRepository();

    private Assignment assignment(String caseId, String resourceId) {
        return new Assignment(caseId, resourceId, ASSIGNED_AT);
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

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get().id());
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

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get().id());
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

        Assignment found = repo.findActiveByCaseId("case-1").get();

        assertEquals(current.id(), found.id());
        assertEquals("r2", found.resourceId());
    }

    @Test
    void otherCasesAssignmentsAreNotMixedUp() {
        Assignment a = assignment("case-1", "r1");
        Assignment b = assignment("case-2", "r2");
        repo.save(a);
        repo.save(b);

        assertEquals(a.id(), repo.findActiveByCaseId("case-1").get().id());
        assertEquals(b.id(), repo.findActiveByCaseId("case-2").get().id());
    }

    // ---------- validation ----------

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findActiveByCaseId(null));
    }
}
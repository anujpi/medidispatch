package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AssignmentManagerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();
    private final AssignmentManager manager =
            new AssignmentManager(assignments, resources, claimer, events, clock);

    // ---------- helpers ----------

    // sets the world up the way the Dispatcher leaves it: unit ASSIGNED, claimed, trip ASSIGNED
    private Assignment dispatched(String caseId, String resourceId) {
        Resource unit = new Resource(resourceId, ResourceType.AMBULANCE_ALS, new Location(0, 0));
        unit.transitionTo(ResourceStatus.ASSIGNED);
        resources.save(unit);
        claimer.tryClaim(resourceId, caseId);
        Assignment a = new Assignment(caseId, resourceId, NOW);
        assignments.save(a);
        return a;
    }

    private AssignmentStatus tripStatus(Assignment a) {
        return assignments.findById(a.id()).get().status();
    }

    private ResourceStatus unitStatus(String resourceId) {
        return resources.findById(resourceId).get().status();
    }

    private void assertUnitIsFree(String resourceId) {
        assertEquals(ResourceStatus.AVAILABLE, unitStatus(resourceId));
        assertTrue(claimer.claimedBy(resourceId).isEmpty());
    }

    // ---------- progress events ----------

    @Test
    void onEnRouteMovesTheTripAndKeepsTheUnitBusy() {
        Assignment a = dispatched("case-1", "r1");

        manager.onEnRoute("case-1");

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void onArrivedMovesTheTripAndKeepsTheUnitBusy() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute("case-1");

        manager.onArrived("case-1");

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    // ---------- complete ----------

    @Test
    void completeFinishesTheTripAndFreesTheUnit() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute("case-1");
        manager.onArrived("case-1");

        manager.complete("case-1");

        assertEquals(AssignmentStatus.DONE, tripStatus(a));
        assertUnitIsFree("r1");
        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aFreedUnitCanBeClaimedByAnotherCase() {
        dispatched("case-1", "r1");
        manager.onEnRoute("case-1");
        manager.onArrived("case-1");
        manager.complete("case-1");

        assertTrue(claimer.tryClaim("r1", "case-2"));
    }

    @Test
    void completeBeforeTheUnitHasArrivedIsRefusedAndChangesNothing() {
        Assignment a = dispatched("case-1", "r1");                  // still ASSIGNED

        assertThrows(IllegalStateException.class, () -> manager.complete("case-1"));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void completingTwiceFailsBecauseThereIsNoActiveAssignmentAnyMore() {
        dispatched("case-1", "r1");
        manager.onEnRoute("case-1");
        manager.onArrived("case-1");
        manager.complete("case-1");

        assertThrows(IllegalStateException.class, () -> manager.complete("case-1"));
    }

    // ---------- cancel ----------

    @Test
    void cancelWorksBeforeTheUnitHasLeft() {
        Assignment a = dispatched("case-1", "r1");

        manager.cancel("case-1");

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelWorksWhileTheUnitIsEnRoute() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute("case-1");

        manager.cancel("case-1");

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelWorksAfterTheUnitHasArrived() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute("case-1");
        manager.onArrived("case-1");

        manager.cancel("case-1");

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelLeavesAnOfflineUnitOfflineButStillReleasesTheClaim() {
        dispatched("case-1", "r1");
        Resource unit = resources.findById("r1").get();
        unit.transitionTo(ResourceStatus.OFFLINE);                   // the ambulance broke down
        resources.save(unit);

        manager.cancel("case-1");

        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
    }

    @Test
    void aCancelledTripCannotBeProgressedAgain() {
        dispatched("case-1", "r1");
        manager.cancel("case-1");

        assertThrows(IllegalStateException.class, () -> manager.onEnRoute("case-1"));
    }

    // ---------- other cases are not affected ----------

    @Test
    void completingOneCaseDoesNotTouchAnotherCasesUnit() {
        dispatched("case-1", "r1");
        dispatched("case-2", "r2");
        manager.onEnRoute("case-1");
        manager.onArrived("case-1");

        manager.complete("case-1");

        assertUnitIsFree("r1");
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r2"));
        assertEquals("case-2", claimer.claimedBy("r2").get());
    }

    // ---------- events ----------

    @Test
    void everyStepOfTheTripPublishesAStatusChange() {
        Assignment a = dispatched("case-1", "r1");

        manager.onEnRoute("case-1");
        manager.onArrived("case-1");
        manager.complete("case-1");

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, NOW),
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.ARRIVED, NOW),
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ARRIVED, AssignmentStatus.DONE, NOW)),
                events.published());
    }

    @Test
    void cancelPublishesAStatusChange() {
        Assignment a = dispatched("case-1", "r1");

        manager.cancel("case-1");

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED, NOW)),
                events.published());
    }

    @Test
    void aRefusedMovePublishesNothing() {
        dispatched("case-1", "r1");                                 // still ASSIGNED

        assertThrows(IllegalStateException.class, () -> manager.complete("case-1"));

        assertTrue(events.published().isEmpty());
    }

    // ---------- errors ----------

    @Test
    void anUnknownCaseFailsForEveryEvent() {
        assertThrows(IllegalStateException.class, () -> manager.onEnRoute("ghost"));
        assertThrows(IllegalStateException.class, () -> manager.onArrived("ghost"));
        assertThrows(IllegalStateException.class, () -> manager.complete("ghost"));
        assertThrows(IllegalStateException.class, () -> manager.cancel("ghost"));
    }

    @Test
    void nullOrBlankCaseIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.onEnRoute(null));
        assertThrows(IllegalArgumentException.class, () -> manager.onArrived("  "));
        assertThrows(IllegalArgumentException.class, () -> manager.complete(null));
        assertThrows(IllegalArgumentException.class, () -> manager.cancel(" "));
    }
    // ---------- timeOut ----------

    @Test
    void timeOutEndsTheTripTakesTheUnitOfflineAndReleasesTheClaim() {
        Assignment a = dispatched("case-1", "r1");

        manager.timeOut("case-1");

        assertEquals(AssignmentStatus.TIMED_OUT, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aTimedOutUnitIsNoLongerOfferedAsAvailable() {
        dispatched("case-1", "r1");

        manager.timeOut("case-1");

        assertTrue(resources.findAvailableNear(
                new Location(0, 0), 10.0, Set.of(ResourceType.AMBULANCE_ALS)).isEmpty());
    }

    @Test
    void timeOutPublishesAStatusChange() {
        Assignment a = dispatched("case-1", "r1");

        manager.timeOut("case-1");

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.TIMED_OUT, NOW)),
                events.published());
    }

    @Test
    void aTripThatIsAlreadyEnRouteCannotBeTimedOut() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute("case-1");
        events.clear();

        assertThrows(IllegalStateException.class, () -> manager.timeOut("case-1"));

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
        assertTrue(events.published().isEmpty());
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentManager(null, resources, claimer, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentManager(assignments, null, claimer, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentManager(assignments, resources, null, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentManager(assignments, resources, claimer, null, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new AssignmentManager(assignments, resources, claimer, events, null));
    }
}
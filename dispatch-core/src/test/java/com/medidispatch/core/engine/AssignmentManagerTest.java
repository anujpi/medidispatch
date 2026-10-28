package com.medidispatch.core.engine;

import com.medidispatch.core.domain.AssignmentRole;
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
        Assignment a = new Assignment(caseId, resourceId, AssignmentRole.PRIMARY, NOW);
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

    // the id of the case's active trip; for a case with none, an id that matches no trip, so refused calls fail the same way
    private String tripOf(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return caseId;                                            // let the manager reject it as before
        }
        List<Assignment> active = assignments.findActiveByCaseId(caseId);
        return active.isEmpty() ? "no-trip-for-" + caseId : active.get(0).id();
    }

    // ---------- progress events ----------

    @Test
    void onEnRouteMovesTheTripAndKeepsTheUnitBusy() {
        Assignment a = dispatched("case-1", "r1");

        manager.onEnRoute(tripOf("case-1"));

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void onArrivedMovesTheTripAndKeepsTheUnitBusy() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));

        manager.onArrived(tripOf("case-1"));

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    // ---------- complete ----------

    @Test
    void completeFinishesTheTripAndFreesTheUnit() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));

        manager.complete(tripOf("case-1"));

        assertEquals(AssignmentStatus.DONE, tripStatus(a));
        assertUnitIsFree("r1");
        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aFreedUnitCanBeClaimedByAnotherCase() {
        dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));
        manager.complete(tripOf("case-1"));

        assertTrue(claimer.tryClaim("r1", "case-2"));
    }

    @Test
    void completeBeforeTheUnitHasArrivedIsRefusedAndChangesNothing() {
        Assignment a = dispatched("case-1", "r1");                  // still ASSIGNED

        assertThrows(IllegalStateException.class, () -> manager.complete(tripOf("case-1")));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void completingTwiceFailsBecauseThereIsNoActiveAssignmentAnyMore() {
        dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));
        manager.complete(tripOf("case-1"));

        assertThrows(IllegalStateException.class, () -> manager.complete(tripOf("case-1")));
    }

    // ---------- cancel ----------

    @Test
    void cancelWorksBeforeTheUnitHasLeft() {
        Assignment a = dispatched("case-1", "r1");

        manager.cancel(tripOf("case-1"));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelWorksWhileTheUnitIsEnRoute() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));

        manager.cancel(tripOf("case-1"));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelWorksAfterTheUnitHasArrived() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));

        manager.cancel(tripOf("case-1"));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertUnitIsFree("r1");
    }

    @Test
    void cancelLeavesAnOfflineUnitOfflineButStillReleasesTheClaim() {
        dispatched("case-1", "r1");
        Resource unit = resources.findById("r1").get();
        unit.transitionTo(ResourceStatus.OFFLINE);                   // the ambulance broke down
        resources.save(unit);

        manager.cancel(tripOf("case-1"));

        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
    }

    @Test
    void aCancelledTripCannotBeProgressedAgain() {
        dispatched("case-1", "r1");
        manager.cancel(tripOf("case-1"));

        assertThrows(IllegalStateException.class, () -> manager.onEnRoute(tripOf("case-1")));
    }

    // ---------- other cases are not affected ----------

    @Test
    void completingOneCaseDoesNotTouchAnotherCasesUnit() {
        dispatched("case-1", "r1");
        dispatched("case-2", "r2");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));

        manager.complete(tripOf("case-1"));

        assertUnitIsFree("r1");
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r2"));
        assertEquals("case-2", claimer.claimedBy("r2").get());
    }

    // ---------- events ----------

    @Test
    void everyStepOfTheTripPublishesAStatusChange() {
        Assignment a = dispatched("case-1", "r1");

        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));
        manager.complete(tripOf("case-1"));

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

        manager.cancel(tripOf("case-1"));

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED, NOW)),
                events.published());
    }

    @Test
    void aRefusedMovePublishesNothing() {
        dispatched("case-1", "r1");                                 // still ASSIGNED

        assertThrows(IllegalStateException.class, () -> manager.complete(tripOf("case-1")));

        assertTrue(events.published().isEmpty());
    }

    // ---------- errors ----------

    @Test
    void anUnknownCaseFailsForEveryEvent() {
        assertThrows(IllegalStateException.class, () -> manager.onEnRoute(tripOf("ghost")));
        assertThrows(IllegalStateException.class, () -> manager.onArrived(tripOf("ghost")));
        assertThrows(IllegalStateException.class, () -> manager.complete(tripOf("ghost")));
        assertThrows(IllegalStateException.class, () -> manager.cancel(tripOf("ghost")));
    }

    @Test
    void nullOrBlankCaseIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.onEnRoute(tripOf(null)));
        assertThrows(IllegalArgumentException.class, () -> manager.onArrived(tripOf("  ")));
        assertThrows(IllegalArgumentException.class, () -> manager.complete(tripOf(null)));
        assertThrows(IllegalArgumentException.class, () -> manager.cancel(tripOf(" ")));
    }
    // ---------- timeOut ----------

    @Test
    void timeOutEndsTheTripTakesTheUnitOfflineAndReleasesTheClaim() {
        Assignment a = dispatched("case-1", "r1");

        manager.timeOut(tripOf("case-1"));

        assertEquals(AssignmentStatus.TIMED_OUT, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aTimedOutUnitIsNoLongerOfferedAsAvailable() {
        dispatched("case-1", "r1");

        manager.timeOut(tripOf("case-1"));

        assertTrue(resources.findAvailableNear(
                new Location(0, 0), 10.0, Set.of(ResourceType.AMBULANCE_ALS)).isEmpty());
    }

    @Test
    void timeOutPublishesAStatusChange() {
        Assignment a = dispatched("case-1", "r1");

        manager.timeOut(tripOf("case-1"));

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.TIMED_OUT, NOW)),
                events.published());
    }

    @Test
    void aTripThatIsAlreadyEnRouteCannotBeTimedOut() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        events.clear();

        assertThrows(IllegalStateException.class, () -> manager.timeOut(tripOf("case-1")));

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("r1"));
        assertEquals("case-1", claimer.claimedBy("r1").get());
        assertTrue(events.published().isEmpty());
    }
    // ---------- onFailed ----------

    @Test
    void aTripThatFailsBeforeLeavingEndsFailedAndTheUnitGoesOffline() {
        Assignment a = dispatched("case-1", "r1");

        manager.onFailed(tripOf("case-1"));

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aTripThatBreaksDownEnRouteEndsFailedAndTheUnitGoesOffline() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));

        manager.onFailed(tripOf("case-1"));

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
    }

    @Test
    void aTripThatFailsAfterArrivalEndsFailedAndTheUnitGoesOffline() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));

        manager.onFailed(tripOf("case-1"));

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
    }

    @Test
    void aFailedUnitIsNotOfferedAsAvailable() {
        dispatched("case-1", "r1");

        manager.onFailed(tripOf("case-1"));

        assertTrue(resources.findAvailableNear(
                new Location(0, 0), 10.0, Set.of(ResourceType.AMBULANCE_ALS)).isEmpty());
    }

    @Test
    void aFailureIsAnnouncedAsAStatusChange() {
        Assignment a = dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        events.clear();

        manager.onFailed(tripOf("case-1"));

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), "case-1", "r1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.FAILED, NOW)),
                events.published());
    }

    @Test
    void aFinishedTripCannotFail() {
        dispatched("case-1", "r1");
        manager.onEnRoute(tripOf("case-1"));
        manager.onArrived(tripOf("case-1"));
        manager.complete(tripOf("case-1"));
        events.clear();

        assertThrows(IllegalStateException.class, () -> manager.onFailed(tripOf("case-1")));

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("r1"));     // the unit is not taken offline afterwards
        assertTrue(events.published().isEmpty());
    }

    @Test
    void aFailureOfAnUnknownCaseIsRefused() {
        assertThrows(IllegalStateException.class, () -> manager.onFailed(tripOf("ghost")));
        assertThrows(IllegalArgumentException.class, () -> manager.onFailed(tripOf(null)));
        assertThrows(IllegalArgumentException.class, () -> manager.onFailed(tripOf(" ")));
    }

    @Test
    void aFailedTripLeavesAnAlreadyOfflineUnitOfflineAndStillReleasesTheClaim() {
        dispatched("case-1", "r1");
        Resource unit = resources.findById("r1").get();
        unit.transitionTo(ResourceStatus.OFFLINE);
        resources.save(unit);

        manager.onFailed(tripOf("case-1"));

        assertEquals(ResourceStatus.OFFLINE, unitStatus("r1"));
        assertTrue(claimer.claimedBy("r1").isEmpty());
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

    // ---------- trips by id, and first responders ----------

    // a first responder on the same case, the way the SupportDispatcher leaves it
    private Assignment supportDispatched(String caseId, String resourceId) {
        Resource unit = new Resource(resourceId, ResourceType.NURSE, new Location(0, 0));
        unit.transitionTo(ResourceStatus.ASSIGNED);
        resources.save(unit);
        claimer.tryClaim(resourceId, caseId);
        Assignment a = new Assignment(caseId, resourceId, AssignmentRole.SUPPORT, NOW);
        assignments.save(a);
        return a;
    }

    @Test
    void everyMoveReturnsTheUpdatedTrip() {
        Assignment a = dispatched("case-1", "als-1");

        Assignment returned = manager.onEnRoute(a.id());

        assertEquals(a.id(), returned.id());
        assertEquals(AssignmentStatus.EN_ROUTE, returned.status());
    }

    @Test
    void anUnknownTripIdIsRejectedAndNothingChanges() {
        Assignment a = dispatched("case-1", "als-1");

        assertThrows(IllegalStateException.class, () -> manager.onEnRoute("no-such-trip"));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(a));
        assertTrue(events.published().isEmpty());
    }

    @Test
    void twoTripsOnOneCaseAreMovedIndependently() {
        Assignment ambulance = dispatched("case-1", "als-1");
        Assignment nurse = supportDispatched("case-1", "nurse-1");

        manager.onEnRoute(nurse.id());

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(nurse));
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(ambulance));
    }

    @Test
    void aFirstResponderArrivingDoesNotTouchTheAmbulance() {
        Assignment ambulance = dispatched("case-1", "als-1");
        Assignment nurse = supportDispatched("case-1", "nurse-1");
        manager.onEnRoute(nurse.id());

        manager.onArrived(nurse.id());

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(nurse));
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(ambulance));
    }

    @Test
    void whenTheAmbulanceArrivesAFirstResponderStillOnTheWayIsCalledOff() {
        Assignment ambulance = dispatched("case-1", "als-1");
        Assignment nurse = supportDispatched("case-1", "nurse-1");
        manager.onEnRoute(ambulance.id());
        manager.onEnRoute(nurse.id());

        manager.onArrived(ambulance.id());

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(ambulance));
        assertEquals(AssignmentStatus.CANCELLED, tripStatus(nurse));
        assertUnitIsFree("nurse-1");                                  // free for the next call
    }

    @Test
    void whenTheAmbulanceArrivesAFirstResponderAlreadyThereIsDone() {
        Assignment ambulance = dispatched("case-1", "als-1");
        Assignment nurse = supportDispatched("case-1", "nurse-1");
        manager.onEnRoute(nurse.id());
        manager.onArrived(nurse.id());
        manager.onEnRoute(ambulance.id());

        manager.onArrived(ambulance.id());

        assertEquals(AssignmentStatus.DONE, tripStatus(nurse));
        assertUnitIsFree("nurse-1");
    }

    @Test
    void theStandDownIsAnnouncedAfterTheArrival() {
        Assignment ambulance = dispatched("case-1", "als-1");
        Assignment nurse = supportDispatched("case-1", "nurse-1");
        manager.onEnRoute(ambulance.id());
        manager.onEnRoute(nurse.id());
        events.clear();

        manager.onArrived(ambulance.id());

        assertEquals(List.of(
                        new AssignmentStatusChanged(ambulance.id(), "case-1", "als-1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.ARRIVED, NOW),
                        new AssignmentStatusChanged(nurse.id(), "case-1", "nurse-1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.CANCELLED, NOW)),
                events.published());
    }
}

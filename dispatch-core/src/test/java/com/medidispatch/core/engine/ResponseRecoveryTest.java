package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ResponseRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);      // about 1.1 km
    private static final Location FAR = new Location(0.1, 0);        // about 11.1 km

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);

    // ---------- helpers ----------

    private void addUnit(String id, Location where) {
        resources.save(new Resource(id, ResourceType.AMBULANCE_ALS, where));
    }

    private Case openCase(String patientId, RiskBand band) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, band, List.of(), NOW);
        return caseManager.handle(a).get();
    }

    private Case criticalCase(String patientId) {
        return openCase(patientId, RiskBand.CRITICAL);
    }

    // dispatches the most urgent case, the way the real flow does: dispatch, then tell the case manager
    private Assignment dispatchAndAssign() {
        Assignment a = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    private AssignmentStatus tripStatus(Assignment a) {
        return assignments.findById(a.id()).get().status();
    }

    private ResourceStatus unitStatus(String id) {
        return resources.findById(id).get().status();
    }

    private Case reload(Case c) {
        return cases.findById(c.id()).get();
    }

    // checks everything that must be true after a unit was lost
    private void assertRecovered(Case c, Assignment a, AssignmentStatus expectedTripStatus) {
        assertEquals(expectedTripStatus, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus(a.resourceId()));
        assertTrue(claimer.claimedBy(a.resourceId()).isEmpty());
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());
    }

    // ---------- onFailed at every stage ----------

    @Test
    void aFailureBeforeTheUnitLeftRecoversTheCase() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();

        recovery.onFailed(c.id());

        assertRecovered(c, a, AssignmentStatus.FAILED);
    }

    @Test
    void aBreakdownEnRouteRecoversTheCase() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());

        recovery.onFailed(c.id());

        assertRecovered(c, a, AssignmentStatus.FAILED);
    }

    @Test
    void aFailureAfterArrivalRecoversTheCase() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());
        assignmentManager.onArrived(c.id());

        recovery.onFailed(c.id());

        assertRecovered(c, a, AssignmentStatus.FAILED);
    }

    // ---------- reassignment after a failure ----------

    @Test
    void theNextNearestUnitIsSentAfterABreakdown() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);
        Case c = criticalCase("p1");
        Assignment first = dispatchAndAssign();
        assertEquals("als-1", first.resourceId());                   // the nearest unit
        assignmentManager.onEnRoute(c.id());

        recovery.onFailed(c.id());                                   // it breaks down on the way
        Assignment second = dispatchAndAssign();                     // the dispatcher runs again

        assertEquals("als-2", second.resourceId());
        assertEquals(c.id(), second.caseId());
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-2"));
        assertEquals(0, queue.size());
    }

    @Test
    void aFailedUnitIsNeverPickedAgainSoTheCaseWaitsInsteadOfLooping() {
        addUnit("als-1", NEAR);                                      // the only unit
        Case c = criticalCase("p1");
        dispatchAndAssign();
        recovery.onFailed(c.id());

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());                               // still waiting for a replacement
    }

    @Test
    void theRequeuedCaseKeepsItsPriorityAheadOfLessUrgentCases() {
        addUnit("als-1", NEAR);
        Case lessUrgent = openCase("p2", RiskBand.MEDIUM);           // waits (no nurse exists)
        Case critical = criticalCase("p1");
        Assignment a = dispatchAndAssign();                          // the critical case goes first
        assertEquals(critical.id(), a.caseId());

        recovery.onFailed(critical.id());

        assertEquals(critical.id(), queue.pollNext().get().id());    // ahead of the medium case again
        assertEquals(lessUrgent.id(), queue.pollNext().get().id());
    }

    // ---------- events ----------

    @Test
    void aFailureIsAnnouncedAsATripChangeThenACaseChange() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());
        events.clear();

        recovery.onFailed(c.id());

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.FAILED, NOW),
                        new CaseStatusChanged(c.id(), "p1",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, NOW)),
                events.published());
    }

    // ---------- onTimedOut ----------

    @Test
    void aTimeoutRecoversTheCaseTheSameWay() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();

        recovery.onTimedOut(c.id());

        assertRecovered(c, a, AssignmentStatus.TIMED_OUT);
    }

    @Test
    void aTimeoutIsAnnouncedAsATripChangeThenACaseChange() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        events.clear();

        recovery.onTimedOut(c.id());

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.TIMED_OUT, NOW),
                        new CaseStatusChanged(c.id(), "p1",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, NOW)),
                events.published());
    }

    @Test
    void aTripThatAlreadyAnsweredCannotBeTimedOutAndNothingChanges() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());
        events.clear();

        assertThrows(IllegalStateException.class, () -> recovery.onTimedOut(c.id()));

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(0, queue.size());
        assertTrue(events.published().isEmpty());
    }

    // ---------- refused calls change nothing ----------

    @Test
    void aCaseWithNoActiveTripCannotBeFailedAndNothingChanges() {
        Case c = criticalCase("p1");                                 // alerted and queued, never dispatched
        events.clear();

        assertThrows(IllegalStateException.class, () -> recovery.onFailed(c.id()));

        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());                               // queued once, not twice
        assertTrue(events.published().isEmpty());
    }

    @Test
    void failingTheSameCaseTwiceIsRefusedTheSecondTime() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        dispatchAndAssign();
        recovery.onFailed(c.id());
        events.clear();

        assertThrows(IllegalStateException.class, () -> recovery.onFailed(c.id()));

        assertEquals(1, queue.size());                               // still queued once
        assertTrue(events.published().isEmpty());
    }

    @Test
    void aFinishedTripCannotBeFailed() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());
        assignmentManager.onArrived(c.id());
        assignmentManager.complete(c.id());

        assertThrows(IllegalStateException.class, () -> recovery.onFailed(c.id()));

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-1"));
    }

    // ---------- a known limit, written down as a test ----------

    @Test
    void knownLimitTheTripIsAlreadyFailedIfTheCaseStepIsRefused() {
        // The dispatcher assigned a unit but nobody called caseManager.onAssigned, so the case is still ALERTED.
        // onFailed ends the trip first (fine), then the case step is refused because ALERTED cannot go to ALERTED.
        // The two steps are not atomic in memory. A transaction in the real adapters would fix this.
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatcher.dispatchNext().get();              // note: no caseManager.onAssigned here

        assertThrows(IllegalStateException.class, () -> recovery.onFailed(c.id()));

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));        // already changed
    }

    // ---------- validation ----------

    @Test
    void nullOrBlankCaseIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> recovery.onFailed(null));
        assertThrows(IllegalArgumentException.class, () -> recovery.onFailed("  "));
        assertThrows(IllegalArgumentException.class, () -> recovery.onTimedOut(null));
        assertThrows(IllegalArgumentException.class, () -> recovery.onTimedOut(" "));
    }

    @Test
    void constructorRejectsMissingManagers() {
        assertThrows(IllegalArgumentException.class, () -> new ResponseRecovery(null, caseManager));
        assertThrows(IllegalArgumentException.class, () -> new ResponseRecovery(assignmentManager, null));
    }

    // ---------- onPreempted ----------

    @Test
    void aPreemptedTripIsCancelledAndTheHealthyUnitGoesBackToTheFleet() {
        addUnit("als-1", NEAR);
        Case c = openCase("p1", RiskBand.HIGH);
        Assignment a = dispatchAndAssign();

        recovery.onPreempted(c.id());

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-1"));       // not OFFLINE: the unit is fine
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());
    }

    @Test
    void aTripThatIsEnRouteCanBePreemptedToo() {
        addUnit("als-1", NEAR);
        Case c = openCase("p1", RiskBand.HIGH);
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());

        recovery.onPreempted(c.id());

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(a));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-1"));
    }

    @Test
    void aPreemptedCaseCanBeServedAgainByTheSameUnitStraightAway() {
        addUnit("als-1", NEAR);
        Case c = openCase("p1", RiskBand.HIGH);
        dispatchAndAssign();
        recovery.onPreempted(c.id());

        Assignment again = dispatchAndAssign();

        assertEquals("als-1", again.resourceId());
        assertEquals(c.id(), again.caseId());
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    @Test
    void aPreemptionIsAnnouncedAsATripChangeThenACaseChange() {
        addUnit("als-1", NEAR);
        Case c = openCase("p1", RiskBand.HIGH);
        Assignment a = dispatchAndAssign();
        events.clear();

        recovery.onPreempted(c.id());

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED, NOW),
                        new CaseStatusChanged(c.id(), "p1",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, NOW)),
                events.published());
    }

    @Test
    void preemptingACaseWithNoActiveTripChangesNothing() {
        assertThrows(IllegalStateException.class, () -> recovery.onPreempted("no-such-case"));

        assertEquals(0, queue.size());
        assertTrue(events.published().isEmpty());
    }
}
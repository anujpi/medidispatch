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
import com.medidispatch.core.response.LadderResponseDecider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TimeoutMonitorTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);      // about 1.1 km
    private static final Location FAR = new Location(0.1, 0);        // about 11.1 km

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
    private final TimeoutMonitor monitor =
            new TimeoutMonitor(assignments, recovery, clock, TIMEOUT);

    // ---------- helpers ----------

    private void addUnit(String id, Location where) {
        resources.save(new Resource(id, ResourceType.AMBULANCE_ALS, where));
    }

    private Case criticalCase(String patientId) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, RiskBand.CRITICAL, List.of(), clock.instant());
        return caseManager.handle(a).get();
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

    // the id of the case's active trip; for a case with none, an id that matches no trip, so refused calls fail the same way
    private String tripOf(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return caseId;                                            // let the manager reject it as before
        }
        List<Assignment> active = assignments.findActiveByCaseId(caseId);
        return active.isEmpty() ? "no-trip-for-" + caseId : active.get(0).id();
    }

    // ---------- nothing is due ----------

    @Test
    void noTripsMeansNothingToDo() {
        assertEquals(0, monitor.checkTimeouts());
    }

    @Test
    void nothingHappensBeforeTheTimeout() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();

        clock.advance(Duration.ofSeconds(59));

        assertEquals(0, monitor.checkTimeouts());
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(a));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    // ---------- a silent trip is timed out ----------

    @Test
    void aSilentTripIsTimedOutTheUnitGoesOfflineAndTheCaseWaitsAgain() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();

        clock.advance(Duration.ofSeconds(61));

        assertEquals(1, monitor.checkTimeouts());
        assertEquals(AssignmentStatus.TIMED_OUT, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());
    }

    @Test
    void exactlyOnTheDeadlineCountsAsTimedOut() {
        addUnit("als-1", NEAR);
        criticalCase("p1");
        dispatchAndAssign();

        clock.advance(TIMEOUT);

        assertEquals(1, monitor.checkTimeouts());
    }

    @Test
    void theSameTripIsNotHandledTwice() {
        addUnit("als-1", NEAR);
        criticalCase("p1");
        dispatchAndAssign();
        clock.advance(Duration.ofSeconds(61));

        assertEquals(1, monitor.checkTimeouts());
        assertEquals(0, monitor.checkTimeouts());
    }

    // ---------- trips that have answered or already ended ----------

    @Test
    void aTripThatIsEnRouteNeverTimesOut() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(tripOf(c.id()));

        clock.advance(Duration.ofMinutes(10));

        assertEquals(0, monitor.checkTimeouts());
        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    @Test
    void aTripThatAlreadyFailedIsIgnoredByTheMonitor() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        recovery.onFailed(tripOf(c.id()));                                   // reported as failed before the deadline
        events.clear();

        clock.advance(Duration.ofSeconds(61));

        assertEquals(0, monitor.checkTimeouts());
        assertEquals(AssignmentStatus.FAILED, tripStatus(a));        // not overwritten as TIMED_OUT
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());                               // queued once, not twice
        assertTrue(events.published().isEmpty());
    }

    // ---------- only overdue trips ----------

    @Test
    void onlyTheOverdueTripsAreHandled() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);

        Case first = criticalCase("pa");
        Assignment a = dispatchAndAssign();                          // assigned at 0 s

        clock.advance(Duration.ofSeconds(40));
        Case second = criticalCase("pb");
        Assignment b = dispatchAndAssign();                          // assigned at 40 s

        clock.advance(Duration.ofSeconds(30));                       // now 70 s: a is 70 s old, b is 30 s old

        assertEquals(1, monitor.checkTimeouts());
        assertEquals(AssignmentStatus.TIMED_OUT, tripStatus(a));
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(b));
        assertEquals(CaseStatus.ALERTED, reload(first).status());
        assertEquals(CaseStatus.RESPONDING, reload(second).status());
    }

    // ---------- reassignment ----------

    @Test
    void theNextNearestUnitIsSentAfterTheFirstOneGoesSilent() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);
        Case c = criticalCase("p1");
        Assignment first = dispatchAndAssign();
        assertEquals("als-1", first.resourceId());                   // the nearest unit

        clock.advance(Duration.ofSeconds(61));                       // it never answers
        monitor.checkTimeouts();
        Assignment second = dispatchAndAssign();                     // the dispatcher runs again

        assertEquals("als-2", second.resourceId());
        assertEquals(c.id(), second.caseId());
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-2"));
        assertEquals(0, queue.size());
    }

    @Test
    void aTimedOutUnitIsNotPickedAgainSoTheCaseWaitsInsteadOfLooping() {
        addUnit("als-1", NEAR);                                      // the only unit
        criticalCase("p1");
        dispatchAndAssign();
        clock.advance(Duration.ofSeconds(61));
        monitor.checkTimeouts();

        assertTrue(dispatcher.dispatchNext().isEmpty());             // als-1 is offline, nobody else exists

        assertEquals(1, queue.size());                               // the case is still waiting
    }

    // ---------- events ----------

    @Test
    void aTimeoutIsAnnouncedAsATripChangeThenACaseChange() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();
        events.clear();

        clock.advance(Duration.ofSeconds(61));
        Instant later = clock.instant();
        monitor.checkTimeouts();

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.TIMED_OUT, later),
                        new CaseStatusChanged(c.id(), "p1",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, later)),
                events.published());
    }

    @Test
    void nothingIsAnnouncedWhenNothingTimesOut() {
        addUnit("als-1", NEAR);
        criticalCase("p1");
        dispatchAndAssign();
        events.clear();

        clock.advance(Duration.ofSeconds(10));
        monitor.checkTimeouts();

        assertTrue(events.published().isEmpty());
    }

    // ---------- construction ----------

    @Test
    void constructorRejectsMissingCollaboratorsAndABadTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(null, recovery, clock, TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(assignments, null, clock, TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(assignments, recovery, null, TIMEOUT));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(assignments, recovery, clock, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(assignments, recovery, clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutMonitor(assignments, recovery, clock, Duration.ofSeconds(-5)));
    }
}

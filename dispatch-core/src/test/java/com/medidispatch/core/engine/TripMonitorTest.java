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

public class TripMonitorTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration SIGNAL = Duration.ofSeconds(30);
    private static final Duration STALL = Duration.ofSeconds(120);

    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);      // about 1.1 km
    private static final Location FAR = new Location(0.1, 0);        // about 11.1 km
    private static final Location NEAR_JITTER = new Location(0.0101, 0);   // 11 m from NEAR: GPS noise

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
    private final UnitTracker tracker = new UnitTracker(resources, clock);
    private final TripMonitor monitor =
            new TripMonitor(assignments, resources, recovery, clock, SIGNAL, STALL);

    // ---------- helpers ----------

    private void addUnit(String id, Location where) {
        resources.save(new Resource(id, ResourceType.AMBULANCE_ALS, where));
    }

    private Case criticalCase(String patientId) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, RiskBand.CRITICAL, List.of(), clock.instant());
        return caseManager.handle(a).get();
    }

    private Assignment dispatchAndAssign() {
        Assignment a = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    // a case, a unit sent to it, and the crew has set off
    private Assignment enRouteTrip(Case c) {
        Assignment a = dispatchAndAssign();
        assignmentManager.onEnRoute(c.id());
        return a;
    }

    private void report(String unitId, Location where) {
        tracker.onLocationReported(unitId, where);
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

    // ---------- nothing wrong ----------

    @Test
    void noTripsMeansNothingToDo() {
        assertEquals(0, monitor.checkTrips());
    }

    @Test
    void aHealthyMovingTripIsLeftAlone() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);

        for (int i = 1; i <= 30; i++) {                               // 5 minutes, a report every 10 s
            clock.advance(Duration.ofSeconds(10));
            report("als-1", new Location(0.01 - i * 0.0003, 0));      // 33 m closer each time
            assertEquals(0, monitor.checkTrips());
        }

        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    // ---------- signal lost ----------

    @Test
    void aUnitThatNeverReportsIsFailedOnceTheSignalTimeoutPasses() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);

        clock.advance(Duration.ofSeconds(29));
        assertEquals(0, monitor.checkTrips());

        clock.advance(Duration.ofSeconds(1));                         // exactly 30 s: counts as expired
        assertEquals(1, monitor.checkTrips());

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());
    }

    @Test
    void silenceIsMeasuredFromTheLastReportNotFromTheTripStart() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        enRouteTrip(c);
        clock.advance(Duration.ofSeconds(10));
        report("als-1", new Location(0.009, 0));                      // moving, then goes quiet

        clock.advance(Duration.ofSeconds(29));
        assertEquals(0, monitor.checkTrips());                        // 29 s since the last report

        clock.advance(Duration.ofSeconds(1));
        assertEquals(1, monitor.checkTrips());                        // 30 s since the last report
    }

    // ---------- stalled ----------

    @Test
    void aUnitThatKeepsReportingFromTheSameSpotIsFailedAsStalled() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);

        for (int i = 1; i <= 11; i++) {                               // alive, but parked, up to 110 s
            clock.advance(Duration.ofSeconds(10));
            report("als-1", NEAR);
            assertEquals(0, monitor.checkTrips());
        }

        clock.advance(Duration.ofSeconds(10));                        // 120 s: exactly the stall timeout
        report("als-1", NEAR);
        assertEquals(1, monitor.checkTrips());

        assertEquals(AssignmentStatus.FAILED, tripStatus(a));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertEquals(CaseStatus.ALERTED, reload(c).status());
    }

    @Test
    void gpsJitterDoesNotSaveAStalledUnit() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        enRouteTrip(c);

        for (int i = 1; i <= 11; i++) {
            clock.advance(Duration.ofSeconds(10));
            report("als-1", i % 2 == 0 ? NEAR : NEAR_JITTER);         // wobbling by 11 m, going nowhere
            assertEquals(0, monitor.checkTrips());
        }
        clock.advance(Duration.ofSeconds(10));
        report("als-1", NEAR_JITTER);

        assertEquals(1, monitor.checkTrips());
    }

    @Test
    void movementBeforeTheTripDoesNotCountAsProgress() {
        addUnit("als-1", FAR);
        report("als-1", NEAR);                                        // moved long ago, lastMovedAt = START
        clock.advance(Duration.ofHours(1));
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);                                // the trip starts an hour later

        clock.advance(Duration.ofSeconds(20));
        report("als-1", NEAR);                                        // alive, parked, but only for 20 s

        assertEquals(0, monitor.checkTrips());                        // not judged stalled for the whole hour
        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
    }

    // ---------- trips the monitor must not touch ----------

    @Test
    void aTripThatIsOnlyAssignedIsLeftToTheTimeoutMonitor() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = dispatchAndAssign();                           // never set off

        clock.advance(Duration.ofMinutes(10));

        assertEquals(0, monitor.checkTrips());
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(a));
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    @Test
    void aUnitThatHasArrivedIsAllowedToStandStill() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);
        assignmentManager.onArrived(c.id());

        clock.advance(Duration.ofMinutes(10));

        assertEquals(0, monitor.checkTrips());
        assertEquals(AssignmentStatus.ARRIVED, tripStatus(a));
    }

    @Test
    void theSameTripIsNotHandledTwice() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        enRouteTrip(c);
        clock.advance(Duration.ofSeconds(31));

        assertEquals(1, monitor.checkTrips());
        assertEquals(0, monitor.checkTrips());
        assertEquals(1, queue.size());                                // queued once, not twice
    }

    // ---------- only the bad trip ----------

    @Test
    void onlyTheStuckTripIsFailedAndTheMovingOneContinues() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);
        Case first = criticalCase("pa");
        Assignment a = enRouteTrip(first);                            // als-1, the nearest
        Case second = criticalCase("pb");
        Assignment b = enRouteTrip(second);                           // als-2

        for (int i = 1; i <= 12; i++) {                               // 120 s
            clock.advance(Duration.ofSeconds(10));
            report("als-1", new Location(0.01 - i * 0.0003, 0));      // moving
            report("als-2", FAR);                                     // parked
        }

        assertEquals(1, monitor.checkTrips());
        assertEquals(AssignmentStatus.EN_ROUTE, tripStatus(a));
        assertEquals(AssignmentStatus.FAILED, tripStatus(b));
        assertEquals(CaseStatus.RESPONDING, reload(first).status());
        assertEquals(CaseStatus.ALERTED, reload(second).status());
    }

    // ---------- reassignment ----------

    @Test
    void afterTheFailureTheNextUnitIsSentAndTheDeadOneIsNeverPickedAgain() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);
        Case c = criticalCase("p1");
        Assignment first = enRouteTrip(c);
        assertEquals("als-1", first.resourceId());

        clock.advance(Duration.ofSeconds(31));                        // als-1 goes silent
        monitor.checkTrips();
        Assignment second = dispatchAndAssign();

        assertEquals("als-2", second.resourceId());
        assertEquals(c.id(), second.caseId());
        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-2"));
    }

    // ---------- events ----------

    @Test
    void aFailedTripIsAnnouncedAsATripChangeThenACaseChange() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        Assignment a = enRouteTrip(c);
        events.clear();

        clock.advance(Duration.ofSeconds(31));
        Instant later = clock.instant();
        monitor.checkTrips();

        assertEquals(List.of(
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.FAILED, later),
                        new CaseStatusChanged(c.id(), "p1",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, later)),
                events.published());
    }

    @Test
    void nothingIsAnnouncedWhenAllTripsAreHealthy() {
        addUnit("als-1", NEAR);
        Case c = criticalCase("p1");
        enRouteTrip(c);
        events.clear();

        clock.advance(Duration.ofSeconds(10));
        report("als-1", new Location(0.009, 0));
        monitor.checkTrips();

        assertTrue(events.published().isEmpty());
    }

    // ---------- bad data and construction ----------

    @Test
    void aTripWhoseUnitIsMissingIsAnError() {
        Assignment orphan = new Assignment("case-1", "ghost", clock.instant());
        orphan.transitionTo(AssignmentStatus.EN_ROUTE);
        assignments.save(orphan);

        assertThrows(IllegalStateException.class, () -> monitor.checkTrips());
    }

    @Test
    void constructorRejectsMissingCollaboratorsAndBadTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(null, resources, recovery, clock, SIGNAL, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, null, recovery, clock, SIGNAL, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, null, clock, SIGNAL, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, recovery, null, SIGNAL, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, recovery, clock, null, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, recovery, clock, SIGNAL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, recovery, clock, Duration.ZERO, STALL));
        assertThrows(IllegalArgumentException.class,
                () -> new TripMonitor(assignments, resources, recovery, clock, SIGNAL, Duration.ofSeconds(-1)));
    }
}
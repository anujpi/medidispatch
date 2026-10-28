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
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PreemptorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    // every patient is at the origin; units sit at increasing distances north of it
    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);       // about 1.1 km
    private static final Location NEAR_2 = new Location(0.015, 0);    // about 1.7 km
    private static final Location FAR = new Location(0.1, 0);         // about 11.1 km

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
            new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
    private final Preemptor preemptor =
            new Preemptor(assignments, cases, resources, patients, new LadderResponseDecider(), recovery, 20.0);

    // ---------- helpers ----------

    private void addUnit(String id, ResourceType type, Location where) {
        resources.save(new Resource(id, type, where));
    }

    private Case openCase(String patientId, RiskBand band) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, band, List.of(), NOW);
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

    // ---------- the basic case ----------

    @Test
    void aWorseCaseTakesAUnitFromALessUrgentOne() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-1"));
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertEquals(CaseStatus.ALERTED, reload(a).status());
        assertEquals(2, queue.size());                                  // a is back, b is still waiting
    }

    @Test
    void itFreesTheUnitButDoesNotSendIt() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        openCase("pa", RiskBand.HIGH);
        dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        preemptor.makeRoomFor(b);

        assertTrue(assignments.findActiveByCaseId(b.id()).isEmpty());   // sending is the Dispatcher's job
    }

    @Test
    void aTripThatIsEnRouteCanLoseItsUnit() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        assignmentManager.onEnRoute(tripOf(a.id()));
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
    }

    // ---------- nobody to take from ----------

    @Test
    void withNoTripsRunningNothingIsTaken() {
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertFalse(preemptor.makeRoomFor(b));

        assertEquals(1, queue.size());
    }

    @Test
    void aCaseOfTheSameBandIsNeverRobbed() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        openCase("pa", RiskBand.CRITICAL);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertFalse(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));
    }

    @Test
    void aMoreUrgentCaseIsNeverRobbed() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        openCase("pa", RiskBand.CRITICAL);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.HIGH);

        assertFalse(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
    }

    @Test
    void aCrewThatHasArrivedIsNeverTaken() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        assignmentManager.onEnRoute(tripOf(a.id()));
        assignmentManager.onArrived(tripOf(a.id()));
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertFalse(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(tripA));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));
    }

    @Test
    void aUnitOfTheWrongTypeForTheWaitingCaseIsLeftAlone() {
        addUnit("bls-1", ResourceType.AMBULANCE_BLS, NEAR);             // fine for HIGH, not for CRITICAL
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertFalse(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
    }

    // ---------- distance and rings ----------

    @Test
    void aUnitBeyondTheSearchRadiusIsNotTaken() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);              // about 11 km
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);
        Preemptor shortReach = new Preemptor(assignments, cases, resources, patients, new LadderResponseDecider(), recovery, 5.0);

        assertFalse(shortReach.makeRoomFor(b));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
    }

    @Test
    void theRingsWidenUntilAUnitIsReached() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);              // 2, 4 and 8 km find nothing; 16 km does
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(b));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
        assertEquals(CaseStatus.ALERTED, reload(a).status());
    }

    // ---------- choosing among several victims ----------

    @Test
    void insideARingTheLeastUrgentCaseLosesItsUnitEvenIfAnotherUnitIsNearer() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-2", ResourceType.AMBULANCE_ALS, NEAR_2);
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();                         // als-1, the nearest
        Case b = openCase("pb", RiskBand.HIGH);
        Assignment tripB = dispatchAndAssign();                         // als-2
        caseManager.handle(new RiskAssessment("pb", 0, RiskBand.MEDIUM, List.of(), NOW));   // b improves while responding
        assertEquals(RiskBand.MEDIUM, reload(b).band());
        Case c = openCase("pc", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(c));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripB));    // the less urgent case lost its unit
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
    }

    @Test
    void withEqualBandsTheNearestUnitIsTaken() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-2", ResourceType.AMBULANCE_ALS, NEAR_2);
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();                         // als-1
        openCase("pb", RiskBand.HIGH);
        Assignment tripB = dispatchAndAssign();                         // als-2
        Case c = openCase("pc", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(c));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripB));
    }

    @Test
    void theNearestRingWinsEvenWhenALessUrgentCaseIsInALaterRing() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-2", ResourceType.AMBULANCE_ALS, FAR);
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();                         // als-1, in the first ring
        Case b = openCase("pb", RiskBand.HIGH);
        Assignment tripB = dispatchAndAssign();                         // als-2, far away
        caseManager.handle(new RiskAssessment("pb", 0, RiskBand.MEDIUM, List.of(), NOW));
        assertEquals(RiskBand.MEDIUM, reload(b).band());
        Case c = openCase("pc", RiskBand.CRITICAL);

        assertTrue(preemptor.makeRoomFor(c));

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));    // the close unit, not the less urgent case
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripB));
    }

    // ---------- bad data and construction ----------

    @Test
    void aWaitingCaseWhosePatientIsMissingIsAnError() {
        Case ghost = new Case("c-ghost", "nobody", RiskBand.CRITICAL, NOW);

        assertThrows(IllegalStateException.class, () -> preemptor.makeRoomFor(ghost));
    }

    @Test
    void anAssignedUnitWithNoActiveTripIsAnInconsistencyNotSomethingToSkip() {
        Resource orphan = new Resource("als-x", ResourceType.AMBULANCE_ALS, NEAR);
        orphan.transitionTo(ResourceStatus.ASSIGNED);
        resources.save(orphan);                                         // ASSIGNED, but on no trip
        Case b = openCase("pb", RiskBand.CRITICAL);

        assertThrows(IllegalStateException.class, () -> preemptor.makeRoomFor(b));
    }

    @Test
    void aMissingWaitingCaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> preemptor.makeRoomFor(null));
    }

    @Test
    void constructorRejectsMissingCollaboratorsAndABadRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(null, cases, resources, patients, new LadderResponseDecider(), recovery, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, null, resources, patients, new LadderResponseDecider(), recovery, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, null, patients, new LadderResponseDecider(), recovery, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, resources, null, new LadderResponseDecider(), recovery, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, resources, patients, null, recovery, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, resources, patients, new LadderResponseDecider(), null, 20.0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, resources, patients, new LadderResponseDecider(), recovery, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Preemptor(assignments, cases, resources, patients, new LadderResponseDecider(), recovery, -5.0));
    }

    // ---------- the ladder decides which units may be taken ----------

    @Test
    void aHighCaseMayTakeABlsFromACaseThatImprovedWhileResponding() {
        addUnit("bls-1", ResourceType.AMBULANCE_BLS, NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();                         // bls-1
        caseManager.handle(new RiskAssessment("pa", 0, RiskBand.MEDIUM, List.of(), NOW));
        assertEquals(RiskBand.MEDIUM, reload(a).band());
        Case b = openCase("pb", RiskBand.HIGH);

        assertTrue(preemptor.makeRoomFor(b));                           // BLS is on b's ladder, a is now less urgent

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
    }
}

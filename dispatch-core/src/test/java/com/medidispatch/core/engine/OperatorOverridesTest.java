package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
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
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.OverrideApplied;
import com.medidispatch.core.event.OverrideKind;
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

/**
 * The dispatcher's overrides on the in-memory engine: each action's effect on the case, its trips, its units and
 * the queue; every refusal leaving nothing changed; and the event carrying the reason.
 */
class OperatorOverridesTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Location HOME = new Location(12.93, 77.6);
    private static final Location NEAR = new Location(12.94, 77.6);       // about 1.1 km
    private static final Location FAR = new Location(12.99, 77.6);        // about 6.7 km

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();
    private final LadderResponseDecider decider = new LadderResponseDecider();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, decider, events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
    private final OperatorOverrides overrides = new OperatorOverrides(cases, queue, caseManager, assignments,
            assignmentManager, recovery, resources, claimer, patients, decider, events, clock);

    // ---------- helpers ----------

    private void addUnit(String id, ResourceType type, Location where) {
        resources.save(new Resource(id, type, where));
    }

    private Case openCase(String patientId, RiskBand band) {
        patients.save(new Patient(patientId, HOME));
        return caseManager.handle(new RiskAssessment(patientId, 0, band, List.of(), NOW)).get();
    }

    // the engine's own dispatch, the way the service runs it
    private Assignment engineSends() {
        Assignment a = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    private CaseStatus caseStatus(Case c) {
        return cases.findById(c.id()).get().status();
    }

    private AssignmentStatus tripStatus(Assignment a) {
        return assignments.findById(a.id()).get().status();
    }

    private ResourceStatus unitStatus(String id) {
        return resources.findById(id).get().status();
    }

    private OverrideApplied lastOverride() {
        List<DomainEvent> all = events.published();
        return (OverrideApplied) all.get(all.size() - 1);
    }

    // ---------- cancel a case ----------

    @Test
    void cancellingAWaitingCaseTakesItOutOfTheQueue() {
        Case c = openCase("p1", RiskBand.CRITICAL);
        assertEquals(1, queue.size());

        Case cancelled = overrides.cancelCase(c.id(), "the son called, she is fine");

        assertEquals(CaseStatus.CANCELLED, cancelled.status());
        assertEquals(0, queue.size());
        OverrideApplied event = lastOverride();
        assertEquals(OverrideKind.CANCEL_CASE, event.kind());
        assertEquals(c.id(), event.caseId());
        assertNull(event.unitId());
        assertEquals("the son called, she is fine", event.reason());
        assertEquals(NOW, event.occurredAt());
    }

    @Test
    void cancellingARespondingCaseFreesEveryUnitOnIt() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment trip = engineSends();
        assignmentManager.onEnRoute(trip.id());

        overrides.cancelCase(c.id(), "false alarm");

        assertEquals(CaseStatus.CANCELLED, caseStatus(c));
        assertEquals(AssignmentStatus.CANCELLED, tripStatus(trip));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-1"));
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertTrue(assignments.findActiveByCaseId(c.id()).isEmpty());
    }

    @Test
    void aCaseOnItsWayToHospitalCannotBeCancelled() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment trip = engineSends();
        assignmentManager.onEnRoute(trip.id());
        assignmentManager.onArrived(trip.id());
        assignmentManager.startTransport(trip.id(), "h-1", java.util.Set.of());
        caseManager.onTransporting(c.id());
        int before = events.size();

        assertThrows(IllegalStateException.class, () -> overrides.cancelCase(c.id(), "oops"));

        assertEquals(CaseStatus.TRANSPORTING, caseStatus(c));
        assertEquals(AssignmentStatus.TRANSPORTING, tripStatus(trip));
        assertEquals(before, events.size());
    }

    @Test
    void cancellingNeedsAReasonAndAKnownCase() {
        Case c = openCase("p1", RiskBand.CRITICAL);

        assertThrows(IllegalArgumentException.class, () -> overrides.cancelCase(c.id(), " "));
        assertThrows(IllegalStateException.class, () -> overrides.cancelCase("no-such-case", "why"));
        assertEquals(CaseStatus.ALERTED, caseStatus(c));
        assertEquals(1, queue.size());
    }

    // ---------- send a unit ----------

    @Test
    void sendingAUnitToAWaitingCaseServesItAndTakesItOutOfTheQueue() {
        addUnit("als-far", ResourceType.AMBULANCE_ALS, FAR);
        Case c = openCase("p1", RiskBand.CRITICAL);

        Assignment sent = overrides.sendUnit(c.id(), "als-far", "veteran crew");

        assertEquals(AssignmentRole.PRIMARY, sent.role());
        assertEquals("als-far", sent.resourceId());
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-far"));
        assertEquals(CaseStatus.RESPONDING, caseStatus(c));
        assertEquals(0, queue.size());
        assertEquals(OverrideKind.SEND_UNIT, lastOverride().kind());
        assertEquals("als-far", lastOverride().unitId());
    }

    @Test
    void sendingAnotherUnitReplacesTheEnginesChoice() {
        addUnit("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-far", ResourceType.AMBULANCE_ALS, FAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment engines = engineSends();
        assertEquals("als-near", engines.resourceId());

        Assignment sent = overrides.sendUnit(c.id(), "als-far", "near crew is new");

        assertEquals(AssignmentStatus.CANCELLED, tripStatus(engines));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-near"));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-far"));
        assertEquals(CaseStatus.RESPONDING, caseStatus(c));
        assertEquals(0, queue.size());                              // not requeued: it has its new unit
        assertEquals(List.of(sent.id()), assignments.findActiveByCaseId(c.id()).stream().map(a -> a.id()).toList());
    }

    @Test
    void anyFreeAmbulanceMayServeAnyCaseButANurseOnlyWhereThePlanSendsOne() {
        addUnit("bls-1", ResourceType.AMBULANCE_BLS, NEAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        addUnit("nurse-2", ResourceType.NURSE, NEAR);
        Case critical = openCase("p1", RiskBand.CRITICAL);           // the plan says ALS only
        Case medium = openCase("p2", RiskBand.MEDIUM);               // the plan says a nurse

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> overrides.sendUnit(critical.id(), "nurse-1", "closest"));
        assertTrue(refused.getMessage().endsWith("send one of: AMBULANCE_ALS, AMBULANCE_BLS"), refused.getMessage());
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("nurse-1"));

        assertEquals("bls-1", overrides.sendUnit(critical.id(), "bls-1", "no ALS free").resourceId());
        assertEquals("nurse-2", overrides.sendUnit(medium.id(), "nurse-2", "she knows the family").resourceId());
    }

    @Test
    void aUnitThatIsNotFreeCannotBeSentAndNothingChanges() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case first = openCase("p1", RiskBand.CRITICAL);
        Assignment busy = engineSends();
        Case second = openCase("p2", RiskBand.CRITICAL);
        int before = events.size();

        assertThrows(IllegalStateException.class, () -> overrides.sendUnit(second.id(), "als-1", "take it"));

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(busy));
        assertEquals(CaseStatus.RESPONDING, caseStatus(first));
        assertEquals(CaseStatus.ALERTED, caseStatus(second));
        assertEquals(1, queue.size());
        assertEquals(before, events.size());
    }

    @Test
    void aUnitAlreadyOnSceneIsNotReplaced() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-2", ResourceType.AMBULANCE_ALS, FAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment trip = engineSends();
        assignmentManager.onEnRoute(trip.id());
        assignmentManager.onArrived(trip.id());

        assertThrows(IllegalStateException.class, () -> overrides.sendUnit(c.id(), "als-2", "swap"));

        assertEquals(AssignmentStatus.ARRIVED, tripStatus(trip));
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("als-2"));
        assertTrue(claimer.claimedBy("als-2").isEmpty());
    }

    @Test
    void aUnitClaimedByAnotherCaseAtTheLastMomentIsRefusedAndNothingChanges() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        claimer.tryClaim("als-1", "someone-else");                  // another dispatch won it first

        assertThrows(IllegalStateException.class, () -> overrides.sendUnit(c.id(), "als-1", "go"));

        assertEquals(CaseStatus.ALERTED, caseStatus(c));
        assertEquals(1, queue.size());
        assertTrue(assignments.findActiveByCaseId(c.id()).isEmpty());
    }

    // ---------- out of service ----------

    @Test
    void aFreeUnitTakenOutOfServiceGoesOffline() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);

        Resource unit = overrides.takeOutOfService("als-1", "end of shift");

        assertEquals(ResourceStatus.OFFLINE, unit.status());
        OverrideApplied event = lastOverride();
        assertEquals(OverrideKind.OUT_OF_SERVICE, event.kind());
        assertNull(event.caseId());
        assertEquals("als-1", event.unitId());
    }

    @Test
    void aUnitOnATripTakenOutOfServiceFailsTheTripAndTheCaseWaitsForTheNextUnit() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("als-2", ResourceType.AMBULANCE_ALS, FAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment trip = engineSends();
        assignmentManager.onEnRoute(trip.id());

        overrides.takeOutOfService("als-1", "flat tyre");

        assertEquals(AssignmentStatus.FAILED, tripStatus(trip));
        assertEquals(ResourceStatus.OFFLINE, unitStatus("als-1"));
        assertEquals(CaseStatus.ALERTED, caseStatus(c));
        assertEquals(c.id(), lastOverride().caseId());
        // and the engine now sends the next-best unit, never the offline one
        assertEquals("als-2", engineSends().resourceId());
    }

    @Test
    void aUnitCarryingAPatientCannotBeTakenOutOfService() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment trip = engineSends();
        assignmentManager.onEnRoute(trip.id());
        assignmentManager.onArrived(trip.id());
        assignmentManager.startTransport(trip.id(), "h-1", java.util.Set.of());
        caseManager.onTransporting(c.id());

        assertThrows(IllegalStateException.class, () -> overrides.takeOutOfService("als-1", "flat tyre"));

        assertEquals(AssignmentStatus.TRANSPORTING, tripStatus(trip));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));
    }

    @Test
    void anOfflineOrUnknownUnitIsRefused() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        overrides.takeOutOfService("als-1", "end of shift");

        assertThrows(IllegalStateException.class, () -> overrides.takeOutOfService("als-1", "again"));
        assertThrows(IllegalStateException.class, () -> overrides.takeOutOfService("no-such-unit", "why"));
        assertThrows(IllegalArgumentException.class, () -> overrides.takeOutOfService("als-1", ""));
    }

    @Test
    void everyPartIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new OperatorOverrides(null, queue, caseManager,
                assignments, assignmentManager, recovery, resources, claimer, patients, decider, events, clock));
    }
}

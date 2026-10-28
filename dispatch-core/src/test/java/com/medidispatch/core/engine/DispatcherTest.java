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
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.response.ResponsePlan;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant OPENED = Instant.parse("2026-10-07T11:50:00Z");

    // every patient is at the origin; units sit at increasing distances north of it
    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);      // about 1.1 km
    private static final Location FAR = new Location(0.1, 0);        // about 11.1 km
    private static final Location TOO_FAR = new Location(1, 0);      // about 111 km, outside the 20 km radius

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20.0);

    // ---------- helpers ----------

    private Case addCase(String id, RiskBand band) {
        patients.save(new Patient("patient-" + id, ORIGIN));
        Case c = new Case(id, "patient-" + id, band, OPENED);
        queue.enqueue(c);
        return c;
    }

    private Resource addResource(String id, ResourceType type, Location where) {
        Resource r = new Resource(id, type, where);
        resources.save(r);
        return r;
    }

    // ---------- nothing to do ----------

    @Test
    void emptyQueueReturnsNothing() {
        assertTrue(dispatcher.dispatchNext().isEmpty());
    }

    // ---------- a successful dispatch ----------

    @Test
    void criticalCaseGetsTheNearestAvailableAlsAmbulance() {
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        assertEquals("case-1", a.caseId());
        assertEquals("als-near", a.resourceId());
        assertEquals(NOW, a.assignedAt());
        assertEquals(ResourceStatus.ASSIGNED, resources.findById("als-near").get().status());
        assertEquals("case-1", claimer.claimedBy("als-near").get());
        assertEquals(0, queue.size());                              // the case left the queue
    }

    @Test
    void theUnitsOfTheOtherCandidatesStayAvailable() {
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        dispatcher.dispatchNext();

        assertTrue(resources.findById("als-far").get().isAvailable());
        assertTrue(claimer.claimedBy("als-far").isEmpty());
    }

    @Test
    void theAssignmentIsStoredAndFindableByCase() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        Assignment stored = assignments.findActiveByCaseId("case-1").get();
        assertEquals(a.id(), stored.id());
        assertEquals(AssignmentStatus.ASSIGNED, stored.status());
    }

    // ---------- events ----------

    @Test
    void aDispatchPublishesExactlyOneUnitAssignedEvent() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        assertEquals(List.of(new UnitAssigned(a.id(), "case-1", "als-1", NOW)), events.published());
    }

    @Test
    void aFailedDispatchPublishesNothing() {
        addCase("case-1", RiskBand.CRITICAL);                       // no units exist

        dispatcher.dispatchNext();

        assertTrue(events.published().isEmpty());
    }

    // ---------- resource types ----------

    @Test
    void criticalCaseNeedsAnAlsAmbulanceEvenIfABlsOneIsNearer() {
        addResource("bls-near", ResourceType.AMBULANCE_BLS, NEAR);
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertEquals("als-far", dispatcher.dispatchNext().get().resourceId());
    }

    @Test
    void highCaseCanUseABlsAmbulance() {
        addResource("bls-near", ResourceType.AMBULANCE_BLS, NEAR);
        addCase("case-1", RiskBand.HIGH);

        assertEquals("bls-near", dispatcher.dispatchNext().get().resourceId());
    }

    @Test
    void mediumCaseGetsANurseNotAnAmbulance() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("nurse-far", ResourceType.NURSE, FAR);
        addCase("case-1", RiskBand.MEDIUM);

        assertEquals("nurse-far", dispatcher.dispatchNext().get().resourceId());
    }

    // ---------- priority ----------

    @Test
    void theMoreUrgentCaseWinsTheOnlyAmbulance() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("high-case", RiskBand.HIGH);                        // queued first on purpose
        addCase("critical-case", RiskBand.CRITICAL);

        Assignment first = dispatcher.dispatchNext().get();
        Optional<Assignment> second = dispatcher.dispatchNext();

        assertEquals("critical-case", first.caseId());
        assertTrue(second.isEmpty());                               // no unit left for the other case
        assertEquals(1, queue.size());                              // it is still waiting
    }

    // ---------- the claim fallback ----------

    @Test
    void aCandidateAlreadyClaimedByAnotherCaseIsSkipped() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        claimer.tryClaim("als-near", "some-other-case");            // someone got there first
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        assertEquals("als-far", a.resourceId());
        assertEquals("some-other-case", claimer.claimedBy("als-near").get());   // untouched
    }

    @Test
    void ifEveryCandidateIsClaimedTheCaseWaits() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        claimer.tryClaim("als-near", "some-other-case");
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());
    }

    // ---------- nobody suitable ----------

    @Test
    void noResourcesMeansNoAssignmentAndTheCaseIsKept() {
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());
    }

    @Test
    void nothingIsStoredWhenNothingWasDispatched() {
        addCase("case-1", RiskBand.CRITICAL);                       // no units exist

        dispatcher.dispatchNext();

        assertTrue(assignments.findActiveByCaseId("case-1").isEmpty());
    }

    @Test
    void aResourceOfTheWrongTypeIsNotUsed() {
        addResource("nurse-1", ResourceType.NURSE, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());
    }

    @Test
    void aResourceOutsideTheRadiusIsNotUsed() {
        addResource("als-remote", ResourceType.AMBULANCE_ALS, TOO_FAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());
    }

    @Test
    void anAssignedUnitIsNotUsedAgain() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);
        addCase("case-2", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isPresent());
        assertTrue(dispatcher.dispatchNext().isEmpty());            // the only unit is taken

        assertEquals(1, queue.size());
    }

    @Test
    void aWaitingCaseIsDispatchedOnceAUnitAppears() {
        addCase("case-1", RiskBand.CRITICAL);
        assertTrue(dispatcher.dispatchNext().isEmpty());            // nothing available yet

        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Optional<Assignment> retry = dispatcher.dispatchNext();

        assertEquals("als-1", retry.get().resourceId());
        assertEquals(0, queue.size());
    }

    // ---------- errors and boundaries ----------

    @Test
    void aMissingPatientThrowsButTheCaseIsNotLost() {
        queue.enqueue(new Case("case-1", "ghost-patient", RiskBand.CRITICAL, OPENED));

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchNext());

        assertEquals(1, queue.size());
    }

    @Test
    void theDispatcherDoesNotChangeTheCaseStatus() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        Case c = addCase("case-1", RiskBand.CRITICAL);

        dispatcher.dispatchNext();

        assertEquals(CaseStatus.MONITORING, c.status());            // the CaseManager owns that later
    }

    @Test
    void constructorRejectsMissingCollaboratorsAndBadRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(null, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, null, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, null, claimer, assignments, new LadderResponseDecider(), events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, null, assignments, new LadderResponseDecider(), events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, null, new LadderResponseDecider(), events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, assignments, null, events, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), null, clock, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, -5));
    }

    // ---------- search rings ----------

    @Test
    void aUnitBeyondTheFirstRingsIsFoundByWidening() {
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);      // 11 km: the 2, 4 and 8 km rings come back empty
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        assertEquals("als-far", a.resourceId());
    }

    @Test
    void aClaimLostInAnInnerRingFallsThroughToALaterRing() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        claimer.tryClaim("als-near", "another-case");                 // someone else already holds the near unit
        addCase("case-1", RiskBand.CRITICAL);

        Assignment a = dispatcher.dispatchNext().get();

        assertEquals("als-far", a.resourceId());
        assertEquals("another-case", claimer.claimedBy("als-near").get());
    }

    @Test
    void aUnitOutsideTheMaximumRadiusIsNeverUsedHoweverFarTheRingsWiden() {
        addResource("als-way-off", ResourceType.AMBULANCE_ALS, TOO_FAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());                                // the case waits
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-way-off").get().status());
    }

    @Test
    void aMaximumRadiusSmallerThanTheFirstRingIsRespected() {
        Dispatcher tight = new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 1.0);
        addResource("als-close", ResourceType.AMBULANCE_ALS, new Location(0.005, 0));   // about 0.6 km
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);                      // about 1.1 km: outside 1 km
        addCase("case-1", RiskBand.CRITICAL);

        assertEquals("als-close", tight.dispatchNext().get().resourceId());

        addCase("case-2", RiskBand.CRITICAL);
        assertTrue(tight.dispatchNext().isEmpty());                   // als-near is outside the 1 km limit
    }

    // ---------- the response ladder ----------

    @Test
    void highCasePrefersABlsEvenWhenAnAlsIsNearer() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("bls-far", ResourceType.AMBULANCE_BLS, FAR);
        addCase("case-1", RiskBand.HIGH);

        assertEquals("bls-far", dispatcher.dispatchNext().get().resourceId());

        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-near").get().status());   // kept free
    }

    @Test
    void highCaseFallsBackToAnAlsWhenNoBlsIsInRange() {
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("bls-way-off", ResourceType.AMBULANCE_BLS, TOO_FAR);
        addCase("case-1", RiskBand.HIGH);

        assertEquals("als-near", dispatcher.dispatchNext().get().resourceId());
    }

    @Test
    void highCaseFallsBackToAnAlsWhenTheOnlyBlsIsClaimedByAnotherCase() {
        addResource("bls-near", ResourceType.AMBULANCE_BLS, NEAR);
        addResource("als-far", ResourceType.AMBULANCE_ALS, FAR);
        claimer.tryClaim("bls-near", "another-case");
        addCase("case-1", RiskBand.HIGH);

        assertEquals("als-far", dispatcher.dispatchNext().get().resourceId());
    }

    @Test
    void theAlsIsStillFreeForTheCriticalCaseThatArrivesNext() {
        addResource("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("bls-1", ResourceType.AMBULANCE_BLS, FAR);
        addCase("high-case", RiskBand.HIGH);
        Assignment forHigh = dispatcher.dispatchNext().get();

        addCase("critical-case", RiskBand.CRITICAL);
        Assignment forCritical = dispatcher.dispatchNext().get();

        assertEquals("bls-1", forHigh.resourceId());
        assertEquals("als-1", forCritical.resourceId());
    }

    @Test
    void criticalCaseDoesNotFallBackToABls() {
        addResource("bls-near", ResourceType.AMBULANCE_BLS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertTrue(dispatcher.dispatchNext().isEmpty());

        assertEquals(1, queue.size());                                // it waits (preemption may help it)
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("bls-near").get().status());
    }

    @Test
    void theDispatcherFollowsWhateverOrderTheDeciderGives() {
        ResponseDecider nurseFirst = (c, p) -> new ResponsePlan(List.of(ResourceType.NURSE, ResourceType.AMBULANCE_ALS));
        Dispatcher custom = new Dispatcher(queue, patients, resources, claimer, assignments, nurseFirst, events, clock, 20.0);
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addResource("nurse-far", ResourceType.NURSE, FAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertEquals("nurse-far", custom.dispatchNext().get().resourceId());
    }

    @Test
    void aQueuedCaseWhosePlanSendsNoUnitIsADataErrorAndTheCaseIsKept() {
        ResponseDecider nobody = (c, p) -> new ResponsePlan(List.of());
        Dispatcher broken = new Dispatcher(queue, patients, resources, claimer, assignments, nobody, events, clock, 20.0);
        addResource("als-near", ResourceType.AMBULANCE_ALS, NEAR);
        addCase("case-1", RiskBand.CRITICAL);

        assertThrows(IllegalStateException.class, () -> broken.dispatchNext());

        assertEquals(1, queue.size());                                // not lost
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-near").get().status());
    }
}
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
import com.medidispatch.core.event.UnitAssigned;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DispatchCoordinatorTest {

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
            new Dispatcher(queue, patients, resources, claimer, assignments, new LadderResponseDecider(), events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
    private final Preemptor preemptor =
            new Preemptor(assignments, cases, resources, claimer, patients, new LadderResponseDecider(), recovery, 20.0);
    private final DispatchCoordinator coordinator = new DispatchCoordinator(dispatcher, preemptor, queue);

    // ---------- helpers ----------

    private void addUnit(String id, Location where) {
        resources.save(new Resource(id, ResourceType.AMBULANCE_ALS, where));
    }

    private Case openCase(String patientId, RiskBand band) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, band, List.of(), NOW);
        return caseManager.handle(a).get();
    }

    // sends the most urgent case through the plain dispatcher, then tells the case manager
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

    // ---------- nothing to do ----------

    @Test
    void anEmptyQueueReturnsNothing() {
        assertTrue(coordinator.dispatchNext().isEmpty());
    }

    // ---------- a free unit comes first ----------

    @Test
    void aFreeUnitIsUsedAndNobodyIsBumped() {
        addUnit("als-1", NEAR);
        addUnit("als-2", FAR);
        openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();                         // als-1
        Case b = openCase("pb", RiskBand.CRITICAL);

        Assignment result = coordinator.dispatchNext().get();

        assertEquals("als-2", result.resourceId());
        assertEquals(b.id(), result.caseId());
        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));     // a keeps its unit
    }

    // ---------- bumping ----------

    @Test
    void withNoFreeUnitALessUrgentTripLosesItsUnitToTheWorseCase() {
        addUnit("als-1", NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);

        Optional<Assignment> result = coordinator.dispatchNext();

        assertTrue(result.isPresent());
        assertEquals("als-1", result.get().resourceId());
        assertEquals(b.id(), result.get().caseId());
        assertEquals(AssignmentStatus.CANCELLED, tripStatus(tripA));
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("als-1"));     // now serving b
        assertEquals(CaseStatus.ALERTED, reload(a).status());
        assertEquals(1, queue.size());
        assertEquals(a.id(), queue.peekNext().get().id());              // a waits, b has left the queue
    }

    @Test
    void theBumpedCaseGetsAnotherUnitWhenOneBecomesFree() {
        addUnit("als-1", NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        dispatchAndAssign();
        openCase("pb", RiskBand.CRITICAL);
        coordinator.dispatchNext();                                     // a is bumped
        addUnit("als-2", FAR);

        Assignment second = coordinator.dispatchNext().get();

        assertEquals(a.id(), second.caseId());
        assertEquals("als-2", second.resourceId());
        assertEquals(0, queue.size());
    }

    @Test
    void aBumpIsAnnouncedAsTripCancelledCaseRequeuedThenUnitAssigned() {
        addUnit("als-1", NEAR);
        Case a = openCase("pa", RiskBand.HIGH);
        Assignment tripA = dispatchAndAssign();
        Case b = openCase("pb", RiskBand.CRITICAL);
        events.clear();

        Assignment result = coordinator.dispatchNext().get();

        assertEquals(List.of(
                        new AssignmentStatusChanged(tripA.id(), a.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED, NOW),
                        new CaseStatusChanged(a.id(), "pa",
                                CaseStatus.RESPONDING, CaseStatus.ALERTED, NOW),
                        new UnitAssigned(result.id(), b.id(), "als-1", NOW)),
                events.published());
    }

    // ---------- nobody can be bumped ----------

    @Test
    void whenNoOneIsRunningAndNoUnitIsFreeTheCaseJustWaits() {
        openCase("pb", RiskBand.CRITICAL);

        assertTrue(coordinator.dispatchNext().isEmpty());

        assertEquals(1, queue.size());
    }

    @Test
    void anEquallyUrgentTripIsNotBumpedSoTheCaseWaits() {
        addUnit("als-1", NEAR);
        openCase("pa", RiskBand.CRITICAL);
        Assignment tripA = dispatchAndAssign();
        openCase("pb", RiskBand.CRITICAL);
        events.clear();

        assertTrue(coordinator.dispatchNext().isEmpty());

        assertEquals(AssignmentStatus.ASSIGNED, tripStatus(tripA));
        assertEquals(1, queue.size());
        assertTrue(events.published().isEmpty());                       // nothing happened, nothing is announced
    }

    // ---------- construction ----------

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class, () -> new DispatchCoordinator(null, preemptor, queue));
        assertThrows(IllegalArgumentException.class, () -> new DispatchCoordinator(dispatcher, null, queue));
        assertThrows(IllegalArgumentException.class, () -> new DispatchCoordinator(dispatcher, preemptor, null));
    }
}

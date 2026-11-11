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
import com.medidispatch.core.event.AssignmentRoleChanged;
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

public class ResponseUpgraderTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    // the patient is at the origin; units sit north of it
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
    private final LadderResponseDecider decider = new LadderResponseDecider();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, decider, events, clock, 20.0);
    private final DispatchCoordinator coordinator = new DispatchCoordinator(dispatcher,
            new Preemptor(assignments, cases, resources, patients, decider, recovery, 20.0),
            new SupportDispatcher(cases, patients, resources, claimer, assignments, decider, events, clock),
            queue);
    private final ResponseUpgrader upgrader =
            new ResponseUpgrader(assignments, resources, patients, decider, assignmentManager, caseManager);

    // ---------- helpers ----------

    private void addUnit(String id, ResourceType type, Location where) {
        resources.save(new Resource(id, type, where));
    }

    private Case openCase(RiskBand band) {
        patients.save(new Patient("p1", ORIGIN));
        return caseManager.handle(new RiskAssessment("p1", 0, band, List.of(), NOW)).get();
    }

    // the patient's band changes while their case is open (the way a new reading changes it)
    private Case becomes(RiskBand band) {
        return caseManager.handle(new RiskAssessment("p1", 0, band, List.of(), NOW)).get();
    }

    private Assignment dispatchAndAssign() {
        Assignment a = coordinator.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    private Assignment reload(Assignment a) {
        return assignments.findById(a.id()).get();
    }

    // ---------- the upgrade ----------

    @Test
    void aNurseCaseThatTurnsCriticalKeepsTheNurseAsFirstResponderAndWaitsForABetterUnit() {
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        openCase(RiskBand.MEDIUM);
        Assignment nurse = dispatchAndAssign();
        events.clear();
        Case c = becomes(RiskBand.CRITICAL);

        assertTrue(upgrader.upgradeIfWorse(c, RiskBand.MEDIUM));

        assertEquals(AssignmentRole.SUPPORT, reload(nurse).role());
        assertEquals(AssignmentStatus.ASSIGNED, reload(nurse).status());     // still on the way
        assertEquals(ResourceStatus.ASSIGNED, resources.findById("nurse-1").get().status());
        assertEquals(CaseStatus.ALERTED, cases.findById(c.id()).get().status());
        assertEquals(1, queue.size());
        assertTrue(events.published().contains(new AssignmentRoleChanged(nurse.id(), c.id(), "nurse-1",
                AssignmentRole.PRIMARY, AssignmentRole.SUPPORT, NOW)));
    }

    @Test
    void thenAnAmbulanceIsSentAndTheNurseStandsDownWhenItArrives() {
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        openCase(RiskBand.MEDIUM);
        Assignment nurse = dispatchAndAssign();
        Case c = becomes(RiskBand.CRITICAL);
        upgrader.upgradeIfWorse(c, RiskBand.MEDIUM);

        Assignment ambulance = dispatchAndAssign();

        assertEquals("als-1", ambulance.resourceId());
        assertEquals(AssignmentRole.PRIMARY, ambulance.role());
        assertEquals(2, assignments.findActiveByCaseId(c.id()).size());    // no second first responder
        assertEquals(CaseStatus.RESPONDING, cases.findById(c.id()).get().status());

        assignmentManager.onEnRoute(ambulance.id());
        assignmentManager.onArrived(ambulance.id());

        assertEquals(AssignmentStatus.CANCELLED, reload(nurse).status());   // stood down, still on the way
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("nurse-1").get().status());
    }

    // ---------- when nothing should change ----------

    @Test
    void aPatientWhoImprovesKeepsTheStrongerUnit() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        openCase(RiskBand.CRITICAL);
        Assignment ambulance = dispatchAndAssign();
        Case c = becomes(RiskBand.MEDIUM);                                  // the MEDIUM plan has no ALS in it

        assertFalse(upgrader.upgradeIfWorse(c, RiskBand.CRITICAL));

        assertEquals(AssignmentRole.PRIMARY, reload(ambulance).role());
        assertEquals(0, queue.size());
    }

    @Test
    void theSameBandChangesNothing() {
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        Case c = openCase(RiskBand.MEDIUM);
        Assignment nurse = dispatchAndAssign();

        assertFalse(upgrader.upgradeIfWorse(cases.findById(c.id()).get(), RiskBand.MEDIUM));

        assertEquals(AssignmentRole.PRIMARY, reload(nurse).role());
    }

    @Test
    void aUnitThatIsStillRightForTheWorseBandKeepsComing() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);                 // HIGH gets the ALS: no BLS around
        openCase(RiskBand.HIGH);
        Assignment ambulance = dispatchAndAssign();
        Case c = becomes(RiskBand.CRITICAL);

        assertFalse(upgrader.upgradeIfWorse(c, RiskBand.HIGH));

        assertEquals(AssignmentRole.PRIMARY, reload(ambulance).role());
        assertEquals(0, queue.size());
    }

    @Test
    void aCaseStillWaitingInTheQueueIsLeftToTheDispatcher() {
        openCase(RiskBand.MEDIUM);                                          // nobody to send: it waits
        Case c = becomes(RiskBand.CRITICAL);

        assertFalse(upgrader.upgradeIfWorse(c, RiskBand.MEDIUM));

        assertEquals(CaseStatus.ALERTED, c.status());
        assertEquals(1, queue.size());
    }

    // ---------- bad input and construction ----------

    @Test
    void missingArgumentsAreRejected() {
        Case c = openCase(RiskBand.MEDIUM);

        assertThrows(IllegalArgumentException.class, () -> upgrader.upgradeIfWorse(null, RiskBand.LOW));
        assertThrows(IllegalArgumentException.class, () -> upgrader.upgradeIfWorse(c, null));
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(null, resources, patients, decider, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(assignments, null, patients, decider, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(assignments, resources, null, decider, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(assignments, resources, patients, null, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(assignments, resources, patients, decider, null, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseUpgrader(assignments, resources, patients, decider, assignmentManager, null));
    }
}

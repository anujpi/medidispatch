package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.scoring.SimpleScorer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

public class FullLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();

    private final SimpleScorer scorer = new SimpleScorer(clock);
    private final CaseManager caseManager = new CaseManager(cases, queue, clock);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer);

    // a reading abnormal enough to score CRITICAL (12 points)
    private VitalsReading criticalReading(String patientId) {
        return new VitalsReading(patientId, NOW, 130, 85, 85, null, 30, 39.0, Consciousness.CONFUSED);
    }

    @Test
    void theSameAmbulanceServesTwoPatientsOneAfterTheOther() {
        patients.save(new Patient("p1", new Location(0, 0)));
        patients.save(new Patient("p2", new Location(0, 0)));
        resources.save(new Resource("als-1", ResourceType.AMBULANCE_ALS, new Location(0.01, 0)));

        // patient 1: from reading to an ambulance on its way
        Case c1 = caseManager.handle(scorer.assess(criticalReading("p1"))).get();
        Assignment a1 = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a1);
        assignmentManager.onEnRoute(c1.id());
        assignmentManager.onArrived(c1.id());
        assertEquals("als-1", a1.resourceId());

        // patient 2 gets critical while the only ambulance is busy: nothing to send, so the case waits
        Case c2 = caseManager.handle(scorer.assess(criticalReading("p2"))).get();
        assertTrue(dispatcher.dispatchNext().isEmpty());
        assertEquals(1, queue.size());

        // patient 1 is stabilised and the case closes: the ambulance comes back
        caseManager.onStabilised(c1.id());
        assignmentManager.complete(c1.id());
        caseManager.close(c1.id());

        assertEquals(CaseStatus.CLOSED, cases.findById(c1.id()).get().status());
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-1").get().status());
        assertTrue(claimer.claimedBy("als-1").isEmpty());

        // the waiting patient now gets the same ambulance
        Assignment a2 = dispatcher.dispatchNext().get();

        assertEquals("als-1", a2.resourceId());
        assertEquals(c2.id(), a2.caseId());
        assertEquals(0, queue.size());
    }

    @Test
    void aCancelledTripSendsTheCaseBackToWaitForAnotherUnit() {
        patients.save(new Patient("p1", new Location(0, 0)));
        resources.save(new Resource("als-1", ResourceType.AMBULANCE_ALS, new Location(0.01, 0)));
        resources.save(new Resource("als-2", ResourceType.AMBULANCE_ALS, new Location(0.05, 0)));

        Case c = caseManager.handle(scorer.assess(criticalReading("p1"))).get();
        Assignment first = dispatcher.dispatchNext().get();
        assertEquals("als-1", first.resourceId());              // the nearest unit

        assignmentManager.cancel(c.id());                       // e.g. the crew never responded

        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-1").get().status());
        assertTrue(assignments.findActiveByCaseId(c.id()).isEmpty());
    }
}
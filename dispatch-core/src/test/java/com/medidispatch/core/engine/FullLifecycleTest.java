package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.VitalsReading;
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
import com.medidispatch.core.scoring.SimpleScorer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();

    private final SimpleScorer scorer = new SimpleScorer(clock);
    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, events, clock, 20.0);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);

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

        // the whole story, as the engine announced it, in order
        assertEquals(List.of(
                        new CaseStatusChanged(c1.id(), "p1", CaseStatus.MONITORING, CaseStatus.ALERTED, NOW),
                        new UnitAssigned(a1.id(), c1.id(), "als-1", NOW),
                        new CaseStatusChanged(c1.id(), "p1", CaseStatus.ALERTED, CaseStatus.RESPONDING, NOW),
                        new AssignmentStatusChanged(a1.id(), c1.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.EN_ROUTE, NOW),
                        new AssignmentStatusChanged(a1.id(), c1.id(), "als-1",
                                AssignmentStatus.EN_ROUTE, AssignmentStatus.ARRIVED, NOW),
                        new CaseStatusChanged(c2.id(), "p2", CaseStatus.MONITORING, CaseStatus.ALERTED, NOW),
                        new CaseStatusChanged(c1.id(), "p1", CaseStatus.RESPONDING, CaseStatus.STABILISED, NOW),
                        new AssignmentStatusChanged(a1.id(), c1.id(), "als-1",
                                AssignmentStatus.ARRIVED, AssignmentStatus.DONE, NOW),
                        new CaseStatusChanged(c1.id(), "p1", CaseStatus.STABILISED, CaseStatus.CLOSED, NOW),
                        new UnitAssigned(a2.id(), c2.id(), "als-1", NOW)),
                events.published());
    }

    @Test
    void aCancelledTripFreesTheUnitAndAnnouncesIt() {
        patients.save(new Patient("p1", new Location(0, 0)));
        resources.save(new Resource("als-1", ResourceType.AMBULANCE_ALS, new Location(0.01, 0)));

        Case c = caseManager.handle(scorer.assess(criticalReading("p1"))).get();
        Assignment a = dispatcher.dispatchNext().get();

        assignmentManager.cancel(c.id());                       // e.g. the crew never responded

        assertEquals(ResourceStatus.AVAILABLE, resources.findById("als-1").get().status());
        assertTrue(claimer.claimedBy("als-1").isEmpty());
        assertTrue(assignments.findActiveByCaseId(c.id()).isEmpty());

        assertEquals(List.of(
                        new CaseStatusChanged(c.id(), "p1", CaseStatus.MONITORING, CaseStatus.ALERTED, NOW),
                        new UnitAssigned(a.id(), c.id(), "als-1", NOW),
                        new AssignmentStatusChanged(a.id(), c.id(), "als-1",
                                AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED, NOW)),
                events.published());
    }
}
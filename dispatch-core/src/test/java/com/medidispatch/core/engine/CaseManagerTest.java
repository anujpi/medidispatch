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
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.scoring.SimpleScorer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class CaseManagerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final CaseManager manager = new CaseManager(cases, queue, clock);

    // ---------- helpers ----------

    private RiskAssessment assessment(String patientId, RiskBand band) {
        return new RiskAssessment(patientId, 0, band, List.of(), NOW);
    }

    private Case reload(Case c) {
        return cases.findById(c.id()).get();
    }

    // moves an ALERTED case all the way to CLOSED
    private void finish(Case c) {
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        c.transitionTo(CaseStatus.CLOSED);
        cases.save(c);
    }

    // ---------- opening cases ----------

    @Test
    void aLowAssessmentForANewPatientCreatesNothing() {
        Optional<Case> result = manager.handle(assessment("p1", RiskBand.LOW));

        assertTrue(result.isEmpty());
        assertEquals(0, queue.size());
        assertTrue(cases.findOpenByPatientId("p1").isEmpty());
    }

    @Test
    void mediumHighAndCriticalAssessmentsOpenAnAlertedQueuedCase() {
        for (RiskBand band : new RiskBand[]{RiskBand.MEDIUM, RiskBand.HIGH, RiskBand.CRITICAL}) {
            String patientId = "patient-" + band;

            Case c = manager.handle(assessment(patientId, band)).get();

            assertEquals(CaseStatus.ALERTED, c.status());
            assertEquals(band, c.band());
            assertEquals(patientId, c.patientId());
            assertEquals(NOW, c.openedAt());
            assertEquals(c.id(), cases.findOpenByPatientId(patientId).get().id());   // saved
        }
        assertEquals(3, queue.size());                                                  // all queued
    }

    // ---------- idempotency ----------

    @Test
    void theSameAssessmentTwiceDoesNotCreateASecondCase() {
        Case first = manager.handle(assessment("p1", RiskBand.HIGH)).get();
        Case second = manager.handle(assessment("p1", RiskBand.HIGH)).get();

        assertEquals(first.id(), second.id());
        assertEquals(1, queue.size());
    }

    // ---------- changing risk on an alerted case ----------

    @Test
    void aWorseBandReRanksTheQueuedCase() {
        manager.handle(assessment("a", RiskBand.HIGH));
        manager.handle(assessment("b", RiskBand.MEDIUM));
        assertEquals(2, queue.size());

        manager.handle(assessment("b", RiskBand.CRITICAL));                  // b gets worse

        assertEquals(2, queue.size());                                       // still one entry each
        assertEquals("b", queue.pollNext().get().patientId());               // b now outranks a
    }

    @Test
    void aLowAssessmentOnAnAlertedCaseSendsItBackToMonitoringAndOutOfTheQueue() {
        Case c = manager.handle(assessment("p1", RiskBand.HIGH)).get();

        manager.handle(assessment("p1", RiskBand.LOW));

        assertEquals(CaseStatus.MONITORING, reload(c).status());
        assertEquals(RiskBand.LOW, reload(c).band());
        assertEquals(0, queue.size());
    }

    @Test
    void aMonitoringCaseThatWorsensIsAlertedAndQueuedAgain() {
        Case c = manager.handle(assessment("p1", RiskBand.HIGH)).get();
        manager.handle(assessment("p1", RiskBand.LOW));                      // recovered: MONITORING

        Case again = manager.handle(assessment("p1", RiskBand.HIGH)).get();  // worse again

        assertEquals(c.id(), again.id());                                    // the same case is reused
        assertEquals(CaseStatus.ALERTED, reload(c).status());
        assertEquals(1, queue.size());
    }

    @Test
    void aMonitoringCaseThatStaysLowStaysOutOfTheQueue() {
        manager.handle(assessment("p1", RiskBand.HIGH));
        manager.handle(assessment("p1", RiskBand.LOW));

        manager.handle(assessment("p1", RiskBand.LOW));

        assertEquals(0, queue.size());
    }

    // ---------- cases that are already being handled ----------

    @Test
    void aRespondingCaseGetsTheNewBandButStaysOutOfTheQueue() {
        Case c = manager.handle(assessment("p1", RiskBand.HIGH)).get();
        queue.pollNext();                                                    // the dispatcher took it
        manager.onAssigned(new Assignment(c.id(), "r1", NOW));

        manager.handle(assessment("p1", RiskBand.CRITICAL));

        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(RiskBand.CRITICAL, reload(c).band());
        assertEquals(0, queue.size());
    }

    @Test
    void aStabilisedCaseGetsTheNewBandAndKeepsItsStatus() {
        Case c = manager.handle(assessment("p1", RiskBand.HIGH)).get();
        c.transitionTo(CaseStatus.RESPONDING);
        c.transitionTo(CaseStatus.STABILISED);
        cases.save(c);
        queue.remove(c.id());

        manager.handle(assessment("p1", RiskBand.LOW));

        assertEquals(CaseStatus.STABILISED, reload(c).status());
        assertEquals(RiskBand.LOW, reload(c).band());
        assertEquals(0, queue.size());
    }

    @Test
    void aClosedCaseIsNotReusedANewOneIsOpened() {
        Case first = manager.handle(assessment("p1", RiskBand.HIGH)).get();
        queue.pollNext();
        finish(first);

        Case second = manager.handle(assessment("p1", RiskBand.HIGH)).get();

        assertNotEquals(first.id(), second.id());
        assertEquals(CaseStatus.CLOSED, reload(first).status());
        assertEquals(CaseStatus.ALERTED, second.status());
        assertEquals(1, queue.size());
    }

    // ---------- onAssigned ----------

    @Test
    void onAssignedMovesAnAlertedCaseToResponding() {
        Case c = manager.handle(assessment("p1", RiskBand.HIGH)).get();

        manager.onAssigned(new Assignment(c.id(), "r1", NOW));

        assertEquals(CaseStatus.RESPONDING, reload(c).status());
    }

    @Test
    void onAssignedForAnUnknownCaseFails() {
        assertThrows(IllegalStateException.class,
                () -> manager.onAssigned(new Assignment("ghost", "r1", NOW)));
    }

    // ---------- validation ----------

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.handle(null));
        assertThrows(IllegalArgumentException.class, () -> manager.onAssigned(null));
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class, () -> new CaseManager(null, queue, clock));
        assertThrows(IllegalArgumentException.class, () -> new CaseManager(cases, null, clock));
        assertThrows(IllegalArgumentException.class, () -> new CaseManager(cases, queue, null));
    }

    // ---------- the whole chain ----------

    @Test
    void aSeverelyAbnormalReadingEndsWithAnAmbulanceOnItsWay() {
        // the full engine wired with in-memory adapters
        InMemoryPatientRepository patients = new InMemoryPatientRepository();
        InMemoryResourceRepository resources = new InMemoryResourceRepository();
        InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
        InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
        SimpleScorer scorer = new SimpleScorer(clock);
        Dispatcher dispatcher = new Dispatcher(queue, patients, resources, claimer, assignments, clock, 20.0);

        patients.save(new Patient("p1", new Location(0, 0)));
        resources.save(new Resource("als-1", ResourceType.AMBULANCE_ALS, new Location(0.01, 0)));

        // reading -> scorer
        VitalsReading reading = new VitalsReading("p1", NOW, 130, 85, 85, null, 30, 39.0, Consciousness.CONFUSED);
        RiskAssessment assessment = scorer.assess(reading);
        assertEquals(RiskBand.CRITICAL, assessment.band());

        // assessment -> case manager -> queue
        Case c = manager.handle(assessment).get();
        assertEquals(CaseStatus.ALERTED, c.status());

        // queue -> dispatcher -> assignment
        Assignment assignment = dispatcher.dispatchNext().get();
        assertEquals(c.id(), assignment.caseId());
        assertEquals("als-1", assignment.resourceId());

        // assignment -> case manager
        manager.onAssigned(assignment);

        assertEquals(CaseStatus.RESPONDING, reload(c).status());
        assertEquals(ResourceStatus.ASSIGNED, resources.findById("als-1").get().status());
        assertEquals(0, queue.size());
    }
}
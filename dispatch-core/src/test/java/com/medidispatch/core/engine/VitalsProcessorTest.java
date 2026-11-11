package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.engine.ProcessingResult.Outcome;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.memory.InMemoryVitalsRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import com.medidispatch.core.scoring.SimpleScorer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class VitalsProcessorTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryVitalsRepository vitals = new InMemoryVitalsRepository();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final LadderResponseDecider decider = new LadderResponseDecider();
    private final SimpleScorer scorer = new SimpleScorer(clock);
    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final ResponseUpgrader upgrader =
            new ResponseUpgrader(assignments, resources, patients, decider, assignmentManager, caseManager);
    private final Dispatcher dispatcher =
            new Dispatcher(queue, patients, resources, claimer, assignments, decider, events, clock, 20.0);
    private final VitalsProcessor processor =
            new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock, MAX_AGE);

    // ---------- helpers ----------

    private void registerPatient(String id) {
        patients.save(new Patient(id, new Location(0, 0)));
    }

    // SpO2 low and a slight fever: SimpleScorer gives 3, MEDIUM
    private VitalsReading medium(String patientId, Instant at) {
        return new VitalsReading(patientId, at, 107, 91, 100, 64, 22, 38.1, Consciousness.ALERT);
    }

    // sends whatever the dispatcher picks for the waiting case, the way the dispatch loop does
    private Assignment dispatchAndAssign() {
        Assignment a = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    // every measurement in the normal range: SimpleScorer gives 0, LOW
    private VitalsReading healthy(String patientId, Instant at) {
        return new VitalsReading(patientId, at, 75, 98, 120, 80, 14, 36.8, Consciousness.ALERT);
    }

    // every measurement abnormal and the patient confused: SimpleScorer gives 12, CRITICAL
    private VitalsReading critical(String patientId, Instant at) {
        return new VitalsReading(patientId, at, 130, 85, 85, 50, 30, 39.0, Consciousness.CONFUSED);
    }

    // ---------- accepted readings ----------

    @Test
    void aCriticalReadingIsStoredScoredAndOpensAQueuedCase() {
        registerPatient("p1");
        VitalsReading reading = critical("p1", START);

        ProcessingResult result = processor.process(reading);

        assertEquals(Outcome.ACCEPTED, result.outcome());
        assertEquals(RiskBand.CRITICAL, result.assessment().get().band());
        assertEquals(CaseStatus.ALERTED, result.currentCase().get().status());
        assertEquals(1, queue.size());
        assertEquals(reading, vitals.latestFor("p1").get());
    }

    @Test
    void aHealthyReadingIsAcceptedButOpensNoCase() {
        registerPatient("p1");

        ProcessingResult result = processor.process(healthy("p1", START));

        assertTrue(result.isAccepted());
        assertEquals(RiskBand.LOW, result.assessment().get().band());
        assertTrue(result.currentCase().isEmpty());
        assertEquals(0, queue.size());
        assertTrue(vitals.latestFor("p1").isPresent());               // stored as history anyway
    }

    @Test
    void aNewerReadingIsAppliedToTheSameCase() {
        registerPatient("p1");
        ProcessingResult first = processor.process(critical("p1", START));
        clock.advance(Duration.ofSeconds(30));

        ProcessingResult second = processor.process(healthy("p1", START.plusSeconds(30)));

        assertTrue(second.isAccepted());
        assertEquals(first.currentCase().get().id(), second.currentCase().get().id());
        assertEquals(CaseStatus.MONITORING, second.currentCase().get().status());   // recovered before help came
        assertEquals(0, queue.size());
    }

    // ---------- rejected readings change nothing ----------

    @Test
    void aReadingForAnUnknownPatientIsIgnored() {
        ProcessingResult result = processor.process(critical("nobody", START));

        assertEquals(Outcome.UNKNOWN_PATIENT, result.outcome());
        assertTrue(vitals.latestFor("nobody").isEmpty());
        assertEquals(0, queue.size());
        assertTrue(events.published().isEmpty());
    }

    @Test
    void aReadingFromTheFutureIsIgnored() {
        registerPatient("p1");

        ProcessingResult result = processor.process(critical("p1", START.plus(Duration.ofMinutes(2))));

        assertEquals(Outcome.FROM_THE_FUTURE, result.outcome());
        assertTrue(vitals.latestFor("p1").isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void aSmallClockDriftIsAllowedUpToTheTolerance() {
        registerPatient("p1");

        ProcessingResult result = processor.process(critical("p1", START.plus(VitalsProcessor.FUTURE_TOLERANCE)));

        assertTrue(result.isAccepted());                               // exactly on the boundary is fine
    }

    @Test
    void aReadingOlderThanMaxAgeIsIgnored() {
        registerPatient("p1");
        clock.advance(Duration.ofMinutes(30));

        ProcessingResult result = processor.process(critical("p1", START));    // taken 30 minutes ago

        assertEquals(Outcome.TOO_OLD, result.outcome());
        assertEquals(0, queue.size());
        assertTrue(events.published().isEmpty());
    }

    @Test
    void aReadingExactlyMaxAgeOldIsStillUsed() {
        registerPatient("p1");
        clock.advance(MAX_AGE);

        assertTrue(processor.process(critical("p1", START)).isAccepted());
    }

    @Test
    void aRepeatedReadingIsADuplicateAndIsNotScoredAgain() {
        registerPatient("p1");
        processor.process(critical("p1", START));
        events.clear();

        ProcessingResult again = processor.process(critical("p1", START));    // redelivered message

        assertEquals(Outcome.DUPLICATE, again.outcome());
        assertEquals(1, queue.size());                                  // still queued once
        assertTrue(events.published().isEmpty());                      // nothing announced twice
    }

    @Test
    void aLateReadingDoesNotOverwriteNewerData() {
        registerPatient("p1");
        processor.process(critical("p1", START.plusSeconds(60)));       // newer reading arrives first
        clock.advance(Duration.ofSeconds(60));

        ProcessingResult late = processor.process(healthy("p1", START));

        assertEquals(Outcome.OUT_OF_ORDER, late.outcome());
        assertEquals(1, queue.size());                                  // the case is still alerted and queued
        assertEquals(START.plusSeconds(60), vitals.latestFor("p1").get().timeStamp());
    }

    // ---------- the patient gets worse while help is on the way ----------

    @Test
    void aNurseCaseThatTurnsCriticalIsQueuedForABetterUnitAndTheNurseKeepsGoing() {
        registerPatient("p1");
        resources.save(new Resource("nurse-1", ResourceType.NURSE, new Location(0.01, 0)));
        processor.process(medium("p1", START));                         // MEDIUM: a nurse is enough
        Assignment nurse = dispatchAndAssign();
        clock.advance(Duration.ofSeconds(60));

        ProcessingResult worse = processor.process(critical("p1", START.plusSeconds(60)));

        assertTrue(worse.isAccepted());
        assertEquals(RiskBand.CRITICAL, worse.currentCase().get().band());
        assertEquals(CaseStatus.ALERTED, worse.currentCase().get().status());   // waiting for a better unit
        assertEquals(1, queue.size());
        assertEquals(AssignmentRole.SUPPORT, assignments.findById(nurse.id()).get().role());
    }

    @Test
    void aPatientWhoImprovesKeepsTheStrongerUnit() {
        registerPatient("p1");
        resources.save(new Resource("als-1", ResourceType.AMBULANCE_ALS, new Location(0.01, 0)));
        processor.process(critical("p1", START));
        Assignment ambulance = dispatchAndAssign();
        clock.advance(Duration.ofSeconds(60));

        processor.process(medium("p1", START.plusSeconds(60)));          // better, but the ALS keeps coming

        assertEquals(AssignmentRole.PRIMARY, assignments.findById(ambulance.id()).get().role());
        assertEquals(0, queue.size());
    }

    // ---------- bad input and construction ----------

    @Test
    void aMissingReadingIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> processor.process(null));
    }

    @Test
    void constructorRejectsMissingCollaboratorsAndABadMaxAge() {
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(null, cases, vitals, scorer, caseManager, upgrader, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, null, vitals, scorer, caseManager, upgrader, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, null, scorer, caseManager, upgrader, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, null, caseManager, upgrader, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, null, upgrader, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, caseManager, null, clock, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, null, MAX_AGE));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new VitalsProcessor(patients, cases, vitals, scorer, caseManager, upgrader, clock,
                        Duration.ofMinutes(-1)));
    }
}

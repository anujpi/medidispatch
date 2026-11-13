package com.medidispatch.sim;

import com.medidispatch.core.engine.AssignmentManager;
import com.medidispatch.core.engine.CaseManager;
import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.Dispatcher;
import com.medidispatch.core.engine.Preemptor;
import com.medidispatch.core.engine.ResponseRecovery;
import com.medidispatch.core.engine.ResponseUpgrader;
import com.medidispatch.core.engine.SupportDispatcher;
import com.medidispatch.core.engine.TimeoutMonitor;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.memory.InMemoryVitalsRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.scoring.SimpleScorer;

import java.time.Clock;
import java.time.Duration;

/**
 * The whole engine, wired with in-memory adapters: the one place that builds it, used by both the step-by-step
 * {@link Simulation} and the threaded {@link LiveSimulation}, so the two can never be wired differently.
 * <p>
 * This is the composition root; Spring will do the same job in the service. The parts are package-private fields,
 * because only the simulator's own classes use them. None of it is thread-safe: whoever owns an EngineParts must
 * use it from one thread only.
 */
final class EngineParts {

    static final Duration ACK_TIMEOUT = Duration.ofSeconds(60);
    static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(30);
    static final Duration STALL_TIMEOUT = Duration.ofSeconds(120);
    static final Duration MAX_READING_AGE = Duration.ofMinutes(10);

    final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    final InMemoryEventPublisher events = new InMemoryEventPublisher();
    final CaseManager caseManager;
    final AssignmentManager assignmentManager;
    final DispatchCoordinator coordinator;
    final UnitTracker unitTracker;
    final TimeoutMonitor timeoutMonitor;
    final TripMonitor tripMonitor;
    final VitalsProcessor processor;

    /**
     * Builds and wires the engine.
     *
     * @param clock          the time every part of the engine reads
     * @param searchRadiusKm how far from a patient units are looked for
     */
    EngineParts(Clock clock, double searchRadiusKm) {
        InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
        ResponseDecider decider = new LadderResponseDecider();

        caseManager = new CaseManager(cases, queue, events, clock);
        assignmentManager = new AssignmentManager(assignments, resources, claimer, events, clock);
        ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
        Dispatcher dispatcher = new Dispatcher(queue, patients, resources, claimer, assignments, decider,
                events, clock, searchRadiusKm);
        Preemptor preemptor = new Preemptor(assignments, cases, resources, patients, decider, recovery, searchRadiusKm);
        SupportDispatcher support = new SupportDispatcher(cases, patients, resources, claimer, assignments,
                decider, events, clock);
        coordinator = new DispatchCoordinator(dispatcher, preemptor, support, queue);
        unitTracker = new UnitTracker(resources, clock);
        timeoutMonitor = new TimeoutMonitor(assignments, recovery, clock, ACK_TIMEOUT);
        tripMonitor = new TripMonitor(assignments, resources, recovery, clock, SIGNAL_TIMEOUT, STALL_TIMEOUT);
        ResponseUpgrader upgrader = new ResponseUpgrader(assignments, resources, patients, decider,
                assignmentManager, caseManager);
        processor = new VitalsProcessor(patients, cases, new InMemoryVitalsRepository(), new SimpleScorer(clock),
                caseManager, upgrader, clock, MAX_READING_AGE);
    }
}

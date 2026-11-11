package com.medidispatch.sim;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.engine.AssignmentManager;
import com.medidispatch.core.engine.CaseManager;
import com.medidispatch.core.engine.DispatchCoordinator;
import com.medidispatch.core.engine.Dispatcher;
import com.medidispatch.core.engine.MutableClock;
import com.medidispatch.core.engine.Preemptor;
import com.medidispatch.core.engine.ResponseRecovery;
import com.medidispatch.core.engine.ResponseUpgrader;
import com.medidispatch.core.engine.SupportDispatcher;
import com.medidispatch.core.engine.TimeoutMonitor;
import com.medidispatch.core.engine.TripMonitor;
import com.medidispatch.core.engine.UnitTracker;
import com.medidispatch.core.engine.VitalsProcessor;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.UnitAssigned;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * A fake city running on the real engine: patients send vitals, units drive to them, some units misbehave,
 * and the engine has to cope.
 * <p>
 * This class is also the <em>composition root</em>: the one place that builds the whole engine (in-memory
 * adapters, managers, dispatcher, monitors) and wires it together. Everything else just receives what it needs.
 * <p>
 * It drives the engine only through its public entry points, and learns what happened only from the engine's
 * events, the way a crew's app would:
 * <ul>
 *   <li>readings go in through {@link VitalsProcessor};</li>
 *   <li>units are sent by {@link DispatchCoordinator};</li>
 *   <li>units report through {@link AssignmentManager} (answered, arrived) and {@link UnitTracker} (position);</li>
 *   <li>{@link TimeoutMonitor} and {@link TripMonitor} catch the misbehaving units.</li>
 * </ul>
 * Call {@link #step()} to move one tick forward, or {@link #run()} to play the whole run and get the report.
 * {@link #snapshot()} gives a read-only picture of the city at any moment, for a window to draw.
 */
public final class Simulation {

    private static final Instant START = Instant.parse("2026-01-05T08:00:00Z");

    /** How often every patient's wearable sends a reading. */
    static final Duration READING_INTERVAL = Duration.ofSeconds(60);
    /** How long an ambulance crew stays with the patient before the case is closed. */
    static final Duration ON_SCENE = Duration.ofMinutes(10);

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration MAX_READING_AGE = Duration.ofMinutes(10);
    private static final double SEARCH_RADIUS_KM = 20.0;

    private final SimulationConfig config;
    private final Instant end;
    private final MutableClock clock = new MutableClock(START);

    // the engine
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();
    private final CaseManager caseManager;
    private final AssignmentManager assignmentManager;
    private final DispatchCoordinator coordinator;
    private final UnitTracker unitTracker;
    private final TimeoutMonitor timeoutMonitor;
    private final TripMonitor tripMonitor;
    private final VitalsProcessor processor;

    // the world (linked maps keep a fixed order, so the same seed gives the same run)
    private final Map<String, SimulatedPatient> patients = new LinkedHashMap<>();
    private final Map<String, SimulatedUnit> units = new LinkedHashMap<>();
    private final Map<String, Random> unitDice = new LinkedHashMap<>();
    private final Map<String, Instant> onSceneSince = new LinkedHashMap<>();
    private final MetricsCollector metrics = new MetricsCollector();
    private final EventLog log = new EventLog(cases);
    private Instant nextReadingAt = START.plus(READING_INTERVAL);
    private int eventsSeen;
    private int eventsLogged;

    /**
     * Builds the engine and the city.
     *
     * @param config the size of the city, the run and the failure chances
     * @throws IllegalArgumentException if {@code config} is missing
     */
    public Simulation(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.end = START.plus(config.duration());

        // 1. the engine, wired by hand (Spring will do this part later)
        InMemoryPatientRepository patientRepo = new InMemoryPatientRepository();
        InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
        ResponseDecider decider = new LadderResponseDecider();

        caseManager = new CaseManager(cases, queue, events, clock);
        assignmentManager = new AssignmentManager(assignments, resources, claimer, events, clock);
        ResponseRecovery recovery = new ResponseRecovery(assignmentManager, caseManager);
        Dispatcher dispatcher = new Dispatcher(queue, patientRepo, resources, claimer, assignments, decider,
                events, clock, SEARCH_RADIUS_KM);
        Preemptor preemptor = new Preemptor(assignments, cases, resources, patientRepo, decider, recovery,
                SEARCH_RADIUS_KM);
        SupportDispatcher support = new SupportDispatcher(cases, patientRepo, resources, claimer, assignments,
                decider, events, clock);
        coordinator = new DispatchCoordinator(dispatcher, preemptor, support, queue);
        unitTracker = new UnitTracker(resources, clock);
        timeoutMonitor = new TimeoutMonitor(assignments, recovery, clock, ACK_TIMEOUT);
        tripMonitor = new TripMonitor(assignments, resources, recovery, clock, SIGNAL_TIMEOUT, STALL_TIMEOUT);
        ResponseUpgrader upgrader = new ResponseUpgrader(assignments, resources, patientRepo, decider,
                assignmentManager, caseManager);
        processor = new VitalsProcessor(patientRepo, cases, new InMemoryVitalsRepository(), new SimpleScorer(clock),
                caseManager, upgrader, clock, MAX_READING_AGE);

        // 2. the city: every random choice comes from the seed
        Random random = new Random(config.seed());
        for (int i = 1; i <= config.patientCount(); i++) {
            SimulatedPatient p = newPatient(String.format("p%02d", i), random);
            patients.put(p.id(), p);
            patientRepo.save(new Patient(p.id(), p.home()));
        }
        addUnits("als", ResourceType.AMBULANCE_ALS, config.alsCount(), random);
        addUnits("bls", ResourceType.AMBULANCE_BLS, config.blsCount(), random);
        addUnits("nurse", ResourceType.NURSE, config.nurseCount(), random);
        addUnits("doctor", ResourceType.DOCTOR, config.doctorCount(), random);
    }

    /**
     * Moves the world forward by one tick.
     *
     * @throws IllegalStateException if the run has already finished
     */
    public void step() {
        if (finished()) {
            throw new IllegalStateException("the simulation has already finished");
        }
        clock.advance(config.tick());
        Instant now = clock.instant();

        sendReadings(now);          // 1. wearables report
        dispatchAll();              // 2. the dispatch loop sends units
        applyNewEvents();           // 3. units hear about new and ended trips
        moveUnits(now);             // 4. units answer, drive, report and arrive
        finishOnScene(now);         // 5. crews that have been on scene long enough finish
        timeoutMonitor.checkTimeouts();   // 6. the engine checks for silent and stuck units
        tripMonitor.checkTrips();
        logNewEvents();                   // 7. readable lines for the window
    }

    /**
     * Plays the rest of the run.
     *
     * @return the report for the whole run
     */
    public SimulationReport run() {
        while (!finished()) {
            step();
        }
        return report();
    }

    /**
     * The report for everything simulated so far.
     *
     * @return the current report
     */
    public SimulationReport report() {
        return metrics.report(events.published(), Duration.between(START, clock.instant()));
    }

    /**
     * A read-only picture of the city right now, for drawing it. It is a copy: changing the simulation later
     * does not change a snapshot already taken.
     *
     * @return every patient and unit, the queue length, the latest events and the statistics so far
     */
    public WorldSnapshot snapshot() {
        List<WorldSnapshot.PatientView> patientViews = new ArrayList<>();
        for (SimulatedPatient p : patients.values()) {
            Optional<RiskBand> band = Optional.empty();
            Optional<Case> open = cases.findOpenByPatientId(p.id());
            if (open.isPresent()) {
                band = Optional.of(open.get().band());
            }
            patientViews.add(new WorldSnapshot.PatientView(p.id(), p.home(), band));
        }
        List<WorldSnapshot.UnitView> unitViews = new ArrayList<>();
        for (SimulatedUnit u : units.values()) {
            Optional<Location> target = Optional.empty();
            Optional<AssignmentRole> role = Optional.empty();
            if (u.busy()) {
                target = Optional.of(u.target());
                role = Optional.of(assignments.findById(u.assignmentId()).orElseThrow().role());
            }
            unitViews.add(new WorldSnapshot.UnitView(u.id(), u.type(), u.position(), stateOf(u), target, role));
        }
        return new WorldSnapshot(now(), CityMap.bounds(), patientViews, unitViews, queue.size(), log.lines(),
                report(), finished());
    }

    /**
     * Says whether the run has reached its end.
     *
     * @return {@code true} once the configured duration has been simulated
     */
    public boolean finished() {
        return !clock.instant().isBefore(end);
    }

    /**
     * The current simulated time.
     *
     * @return now, in simulated time
     */
    public Instant now() {
        return clock.instant();
    }

    // ---------- the steps ----------

    private void sendReadings(Instant now) {
        if (now.isBefore(nextReadingAt)) {
            return;
        }
        for (SimulatedPatient p : patients.values()) {
            metrics.onReading(processor.process(p.readingAt(now)));
        }
        nextReadingAt = nextReadingAt.plus(READING_INTERVAL);
    }

    private void dispatchAll() {
        while (true) {
            Optional<Assignment> sent = coordinator.dispatchNext();
            if (sent.isEmpty()) {
                return;                                       // nothing waiting, or nobody can be sent now
            }
            caseManager.onAssigned(sent.get());
        }
    }

    // the simulator learns about trips only from the engine's events, like a crew's app would
    private void applyNewEvents() {
        List<DomainEvent> all = events.published();
        for (int i = eventsSeen; i < all.size(); i++) {
            DomainEvent e = all.get(i);
            if (e instanceof UnitAssigned assigned) {
                startTrip(assigned);
            } else if (e instanceof AssignmentStatusChanged changed && changed.to().isTerminal()) {
                endTrip(changed);
            }
        }
        eventsSeen = all.size();
    }

    private void moveUnits(Instant now) {
        for (SimulatedUnit unit : units.values()) {
            if (!unit.busy()) {
                continue;
            }
            String tripId = unit.assignmentId();
            Assignment trip = assignments.findById(tripId).orElseThrow();
            if (trip.status().isTerminal()) {
                unit.free();                                  // ended earlier this step (for example stood down)
                continue;
            }
            SimulatedUnit.TickResult result = unit.tick(config.tick());
            if (result == SimulatedUnit.TickResult.ACKNOWLEDGED) {
                assignmentManager.onEnRoute(tripId);
            }
            if (unit.reportsPosition()) {
                unitTracker.onLocationReported(unit.id(), unit.position());
            }
            if (result == SimulatedUnit.TickResult.ARRIVED) {
                Assignment arrived = assignmentManager.onArrived(tripId);
                if (arrived.role() == AssignmentRole.PRIMARY) {
                    patientOf(arrived).treat();               // the ambulance takes over care
                    onSceneSince.put(tripId, now);
                }
            }
        }
    }

    private void finishOnScene(Instant now) {
        List<String> finished = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : onSceneSince.entrySet()) {
            if (!now.isBefore(entry.getValue().plus(ON_SCENE))) {
                finished.add(entry.getKey());
            }
        }
        for (String tripId : finished) {
            onSceneSince.remove(tripId);
            Assignment done = assignmentManager.complete(tripId);
            caseManager.onStabilised(done.caseId());
            caseManager.close(done.caseId());
        }
    }

    // the event log has its own bookmark, so it also sees events from the end of a step
    private void logNewEvents() {
        List<DomainEvent> all = events.published();
        for (int i = eventsLogged; i < all.size(); i++) {
            log.record(all.get(i));
        }
        eventsLogged = all.size();
    }

    // ---------- helpers ----------

    // what a unit is doing, for the snapshot; the engine's OFFLINE wins, because a failed unit stays out of service
    private WorldSnapshot.UnitState stateOf(SimulatedUnit u) {
        if (resources.findById(u.id()).orElseThrow().status() == ResourceStatus.OFFLINE) {
            return WorldSnapshot.UnitState.OFFLINE;
        }
        if (!u.busy()) {
            return WorldSnapshot.UnitState.FREE;
        }
        if (u.broken()) {
            return WorldSnapshot.UnitState.BROKEN_DOWN;
        }
        if (u.silent()) {
            return WorldSnapshot.UnitState.NO_SIGNAL;
        }
        if (!u.acknowledged()) {
            return WorldSnapshot.UnitState.ANSWERING;
        }
        return u.arrived() ? WorldSnapshot.UnitState.ON_SCENE : WorldSnapshot.UnitState.DRIVING;
    }

    private void startTrip(UnitAssigned assigned) {
        SimulatedUnit unit = units.get(assigned.resourceId());
        Case c = cases.findById(assigned.caseId()).orElseThrow();
        Location target = patients.get(c.patientId()).home();
        unit.assign(assigned.assignmentId(), target, behaviourFor(unit.id()));
    }

    private void endTrip(AssignmentStatusChanged changed) {
        SimulatedUnit unit = units.get(changed.resourceId());
        if (changed.assignmentId().equals(unit.assignmentId())) {
            unit.free();
        }
        onSceneSince.remove(changed.assignmentId());
    }

    private SimulatedPatient patientOf(Assignment trip) {
        Case c = cases.findById(trip.caseId()).orElseThrow();
        return patients.get(c.patientId());
    }

    // each unit has its own dice, so its behaviour depends only on the seed and how many trips it has had
    private SimulatedUnit.Behaviour behaviourFor(String unitId) {
        double roll = unitDice.get(unitId).nextDouble();
        if (roll < config.noAckChance()) {
            return SimulatedUnit.Behaviour.NEVER_ACKNOWLEDGES;
        }
        if (roll < config.noAckChance() + config.breakdownChance()) {
            return SimulatedUnit.Behaviour.BREAKS_DOWN;
        }
        if (roll < config.noAckChance() + config.breakdownChance() + config.signalLossChance()) {
            return SimulatedUnit.Behaviour.LOSES_SIGNAL;
        }
        return SimulatedUnit.Behaviour.NORMAL;
    }

    private SimulatedPatient newPatient(String id, Random random) {
        Location home = CityMap.randomPoint(random);
        boolean fallsIll = random.nextDouble() < config.deteriorationChance();
        // fall ill between 5 minutes in and 40 minutes before the end, so there is time to respond
        long earliest = 5 * 60;
        long latest = Math.max(earliest + 1, config.duration().toSeconds() - 40 * 60);
        Instant when = START.plusSeconds(earliest + (long) (random.nextDouble() * (latest - earliest)));
        double level = random.nextDouble();
        double peak = level < 0.3 ? SimulatedPatient.MEDIUM_PEAK
                    : level < 0.6 ? SimulatedPatient.HIGH_PEAK
                    : SimulatedPatient.CRITICAL_PEAK;
        return new SimulatedPatient(id, home, fallsIll ? when : null, peak);
    }

    private void addUnits(String prefix, ResourceType type, int count, Random random) {
        for (int i = 1; i <= count; i++) {
            String id = prefix + "-" + i;
            SimulatedUnit unit = new SimulatedUnit(id, type, CityMap.randomPoint(random));
            units.put(id, unit);
            unitDice.put(id, new Random(config.seed() * 31 + id.hashCode()));
            resources.save(new Resource(id, type, unit.position()));
        }
    }
}

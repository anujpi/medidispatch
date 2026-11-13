package com.medidispatch.sim;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.event.AssignmentRoleChanged;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.sim.LiveUnit.TripOrder;
import com.medidispatch.sim.WorldSnapshot.PatientView;
import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine side of the live simulation: the real dispatch engine, owned by exactly one thread.
 * <p>
 * The engine's parts are not thread-safe, so instead of locking them, only one thread ever touches them: the
 * "engine" thread. Other threads (patients, units) never call the engine; they hand it a small job through
 * {@link #reading}, {@link #answered}, {@link #position}, {@link #arrived} and {@link #backInService}, and the
 * engine thread runs the jobs one at a time, in the order they came. This is <em>thread confinement</em>, the same
 * idea as an actor or a single event loop.
 * <p>
 * Every {@link #BEAT} the engine thread also runs a tick: send units, finish crews on scene, check for silent and
 * stuck units, tell units about new, changed and ended trips (from the engine's events), and publish a fresh
 * {@link WorldSnapshot}. The snapshot is immutable and kept in a {@code volatile} field, so the window can read the
 * latest one from its own thread at any time.
 */
final class LiveEngine {

    /** How often the engine ticks, in real time. */
    static final Duration BEAT = Duration.ofMillis(25);

    private final ScaledClock clock;
    private final Instant start;
    private final WorldSnapshot.CityBounds bounds;
    private final EngineParts engine;
    private final ScheduledExecutorService thread;
    private final AtomicInteger errors = new AtomicInteger();

    // only ever used on the engine thread (or before it starts)
    private final Map<String, LivePatient> patients = new LinkedHashMap<>();
    private final Map<String, LiveUnit> units = new LinkedHashMap<>();
    private final Map<String, Instant> onSceneSince = new LinkedHashMap<>();
    private final MetricsCollector metrics = new MetricsCollector();
    private final EventLog log;
    private int eventsSeen;

    private volatile WorldSnapshot latest;

    /**
     * Builds the engine. Nothing runs until {@link #start()}.
     *
     * @param clock  the live simulation's clock
     * @param config the city size, which sets the map edges and how far units are looked for
     */
    LiveEngine(ScaledClock clock, LiveConfig config) {
        this.clock = clock;
        this.start = clock.instant();
        this.bounds = CityMap.bounds(config.citySizeKm());
        this.engine = new EngineParts(clock, config.searchRadiusKm());
        this.log = new EventLog(engine.cases);
        this.thread = Executors.newSingleThreadScheduledExecutor((Runnable work) -> {
            Thread t = new Thread(work, "engine");
            t.setDaemon(true);                                // never keeps the program alive on its own
            return t;
        });
    }

    // ---------- setup, before start ----------

    /**
     * Adds a patient to the city. Call before {@link #start()}.
     *
     * @param patient the patient
     */
    void addPatient(LivePatient patient) {
        patients.put(patient.id(), patient);
        engine.patients.save(new Patient(patient.id(), patient.home()));
    }

    /**
     * Adds a unit to the city, free where it stands. Call before {@link #start()}.
     *
     * @param unit the unit
     */
    void addUnit(LiveUnit unit) {
        UnitView v = unit.view();
        units.put(v.id(), unit);
        engine.resources.save(new Resource(v.id(), v.type(), v.position()));
    }

    /** Publishes the first snapshot, so there is a city to show before {@link #start()}. Call after setup. */
    void publishFirstSnapshot() {
        latest = snapshot(clock.instant());
    }

    /** Starts ticking. */
    void start() {
        long beat = BEAT.toMillis();
        thread.scheduleWithFixedDelay(guarded(() -> tick()), beat, beat, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the engine thread and waits briefly for it to finish.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    void stop() throws InterruptedException {
        thread.shutdownNow();
        thread.awaitTermination(2, TimeUnit.SECONDS);
    }

    boolean stopped() {
        return thread.isTerminated();
    }

    /**
     * The latest picture of the city. Safe to call from any thread.
     *
     * @return the snapshot from the latest tick
     */
    WorldSnapshot latestSnapshot() {
        return latest;
    }

    /**
     * How many jobs or ticks have failed with an exception. Should stay 0; a failure is printed and the
     * simulation carries on.
     *
     * @return the number of failures so far
     */
    int errors() {
        return errors.get();
    }

    /**
     * Wraps work so an exception is counted and printed instead of escaping. A periodic task that throws is
     * silently cancelled by its executor, so every repeating task in the live simulation is wrapped in this.
     *
     * @param work the work
     * @return the same work, made safe
     */
    Runnable guarded(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                errors.incrementAndGet();
                e.printStackTrace();
            }
        };
    }

    // ---------- jobs from other threads ----------

    /** A wearable reading arrives. */
    void reading(VitalsReading reading) {
        onEngineThread(() -> metrics.onReading(engine.processor.process(reading)));
    }

    /** A crew answered its trip. Ignored if the trip has meanwhile ended or timed out. */
    void answered(String assignmentId) {
        onEngineThread(() -> {
            if (statusOf(assignmentId) == AssignmentStatus.ASSIGNED) {
                engine.assignmentManager.onEnRoute(assignmentId);
            }
        });
    }

    /** A unit reports where it is. */
    void position(String unitId, Location where) {
        onEngineThread(() -> engine.unitTracker.onLocationReported(unitId, where));
    }

    /** A unit reached its patient. Ignored if the trip has meanwhile ended (for example stood down). */
    void arrived(String assignmentId) {
        onEngineThread(() -> {
            if (statusOf(assignmentId) != AssignmentStatus.EN_ROUTE) {
                return;
            }
            Assignment trip = engine.assignmentManager.onArrived(assignmentId);
            if (trip.role() == AssignmentRole.PRIMARY) {
                patients.get(patientIdOf(trip.caseId())).treat();     // the ambulance takes over care
                onSceneSince.put(assignmentId, clock.instant());
            }
        });
    }

    /** A repaired unit is ready again. */
    void backInService(String unitId) {
        onEngineThread(() -> {
            if (engine.resources.findById(unitId).orElseThrow().status() == ResourceStatus.OFFLINE) {
                engine.unitTracker.onBackInService(unitId);
            }
        });
    }

    // queues the job for the engine thread; once stopped, jobs are dropped
    private void onEngineThread(Runnable job) {
        try {
            thread.execute(guarded(job));
        } catch (RejectedExecutionException stopping) {
            // the simulation is shutting down; nothing left to do
        }
    }

    // ---------- the tick, on the engine thread ----------

    private void tick() {
        Instant now = clock.instant();
        dispatchAll();
        finishOnScene(now);
        engine.timeoutMonitor.checkTimeouts();
        engine.tripMonitor.checkTrips();
        readNewEvents();
        latest = snapshot(now);
    }

    private void dispatchAll() {
        while (true) {
            Optional<Assignment> sent = engine.coordinator.dispatchNext();
            if (sent.isEmpty()) {
                return;                                       // nothing waiting, or nobody can be sent now
            }
            engine.caseManager.onAssigned(sent.get());
        }
    }

    private void finishOnScene(Instant now) {
        List<String> finished = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : onSceneSince.entrySet()) {
            if (!now.isBefore(entry.getValue().plus(Simulation.ON_SCENE))) {
                finished.add(entry.getKey());
            }
        }
        for (String tripId : finished) {
            onSceneSince.remove(tripId);
            Assignment done = engine.assignmentManager.complete(tripId);
            engine.caseManager.onStabilised(done.caseId());
            engine.caseManager.close(done.caseId());
        }
    }

    // units learn about their trips only from the engine's events, like a crew's app would
    // ponytail: the in-memory event list grows forever (a few MB per simulated day); trim it if runs last for weeks
    private void readNewEvents() {
        List<DomainEvent> fresh = engine.events.publishedSince(eventsSeen);
        eventsSeen += fresh.size();
        for (DomainEvent e : fresh) {
            log.record(e);
            metrics.onEvent(e);
            if (e instanceof UnitAssigned a) {
                Location target = patients.get(patientIdOf(a.caseId())).home();
                units.get(a.resourceId()).order(
                        new TripOrder(TripOrder.Kind.START, a.assignmentId(), target, a.role(), null));
            } else if (e instanceof AssignmentRoleChanged c) {
                units.get(c.resourceId()).order(
                        new TripOrder(TripOrder.Kind.ROLE, c.assignmentId(), null, c.to(), null));
            } else if (e instanceof AssignmentStatusChanged s && s.to().isTerminal()) {
                onSceneSince.remove(s.assignmentId());
                units.get(s.resourceId()).order(
                        new TripOrder(TripOrder.Kind.END, s.assignmentId(), null, null, s.to()));
            }
        }
    }

    private WorldSnapshot snapshot(Instant now) {
        List<PatientView> patientViews = new ArrayList<>();
        for (LivePatient p : patients.values()) {
            Optional<RiskBand> band = Optional.empty();
            Optional<Case> open = engine.cases.findOpenByPatientId(p.id());
            if (open.isPresent()) {
                band = Optional.of(open.get().band());
            }
            patientViews.add(new PatientView(p.id(), p.home(), band));
        }
        List<UnitView> unitViews = new ArrayList<>();
        for (LiveUnit u : units.values()) {
            UnitView v = u.view();
            // the engine may have taken it offline a moment before the unit heard about it
            boolean offline = engine.resources.findById(v.id()).orElseThrow().status() == ResourceStatus.OFFLINE;
            if (offline && v.state() != UnitState.OFFLINE) {
                v = new UnitView(v.id(), v.type(), v.position(), UnitState.OFFLINE, v.target(), v.role());
            }
            unitViews.add(v);
        }
        return new WorldSnapshot(now, bounds, patientViews, unitViews, engine.queue.size(), log.lines(),
                metrics.report(Duration.between(start, now)), false);
    }

    private AssignmentStatus statusOf(String assignmentId) {
        return engine.assignments.findById(assignmentId).orElseThrow().status();
    }

    private String patientIdOf(String caseId) {
        return engine.cases.findById(caseId).orElseThrow().patientId();
    }
}

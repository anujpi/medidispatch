package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.sim.LiveUnit.TripOrder;
import com.medidispatch.sim.TripWatcher.Change;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The city's link to the RUNNING dispatch service, over HTTP: what {@link LiveEngine} does in memory, done by calling
 * the service's REST API, the way real wearables and crew phones will.
 * <p>
 * <b>Two clocks.</b> The city keeps its own sped-up {@link ScaledClock} for the pace of the world (how fast illnesses
 * develop, ambulances drive, crews work on scene). The service runs on real time, so everything sent to it carries
 * real time: readings are re-stamped with {@code Instant.now()}. The service's own rules (60 s to answer, 30 s of
 * silence, 2 minutes stalled) stay in real seconds.
 * <p>
 * <b>Threads.</b>
 * <ul>
 *   <li>one <b>sender</b> thread sends every request IN ORDER (an accept must reach the service before the arrive)
 *       and owns the trip bookkeeping, so that needs no locks;</li>
 *   <li>a <b>timer</b> thread flushes positions once a second and checks for crews whose time on scene is up;</li>
 *   <li>the <b>live-feed</b> thread ({@link LiveFeedReader}) reads the service's live picture and, through a
 *       {@link TripWatcher}, tells crews about new, changed and ended trips.</li>
 * </ul>
 * Agents call the {@link CityLink} methods from their own threads; each call only queues work and returns.
 * <p>
 * <b>Positions are combined.</b> A driving unit reports every 25 ms; only each unit's latest position is kept and
 * sent at most once a second, which is all the service needs to see that it moves (or, for a broken-down unit that
 * keeps reporting the same spot, that it does not).
 * <p>
 * Failed requests are counted (409s separately: in a live system "that trip has already ended" is normal) and never
 * stop the city.
 */
final class HttpLink implements CityLink {

    private static final long POSITION_FLUSH_MS = 1_000;
    private static final long ON_SCENE_CHECK_MS = 250;
    private static final int ERRORS_PRINTED = 20;

    private final ServiceClient service;
    private final ScaledClock clock;
    private final TripWatcher watcher = new TripWatcher();
    private final LiveFeedReader feed;
    private final ExecutorService sender;
    private final ScheduledExecutorService timers;
    private final ConcurrentHashMap<String, Location> pendingPositions = new ConcurrentHashMap<>();

    // set once before start; the agents themselves are thread-safe
    private Map<String, LivePatient> patients = Map.of();
    private Map<String, LiveUnit> units = Map.of();

    // only ever used on the sender thread
    private final Map<String, Change> trips = new HashMap<>();
    private final Map<String, Instant> onSceneSince = new LinkedHashMap<>();

    private final AtomicLong readings = new AtomicLong();
    private final AtomicLong tripsSeen = new AtomicLong();
    private final AtomicLong tripsFinished = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile boolean stopping;

    /**
     * @param service the running service
     * @param clock   the city's clock (the pace of the world; not what is sent to the service)
     */
    HttpLink(ServiceClient service, ScaledClock clock) {
        this.service = service;
        this.clock = clock;
        this.feed = new LiveFeedReader(service, (String data) -> onSnapshot(data), () -> error("live feed lost"));
        this.sender = Executors.newSingleThreadExecutor((Runnable work) -> daemon(work, "http-sender"));
        this.timers = Executors.newSingleThreadScheduledExecutor((Runnable work) -> daemon(work, "http-timers"));
    }

    /**
     * Tells the link which agents to hand trip orders and treatments to. Call once, before {@link #start()}.
     *
     * @param patients every patient, by id
     * @param units    every unit, by id
     */
    void connect(Map<String, LivePatient> patients, Map<String, LiveUnit> units) {
        this.patients = Map.copyOf(patients);
        this.units = Map.copyOf(units);
    }

    /**
     * Registers the city in the service: facilities first (units need their base), then units, then patients.
     * Runs on the caller's thread and waits for every answer.
     *
     * @param plan what to register
     * @throws IllegalStateException if the service already has facilities (the database is not empty) or refuses
     *                               something
     */
    void setUp(BangaloreCity.Plan plan) {
        ServiceClient.Response existing = service.get("/api/facilities");
        if (!existing.ok()) {
            throw new IllegalStateException("GET /api/facilities answered " + existing.status());
        }
        if (existing.body() != null && existing.body().size() > 0) {
            throw new IllegalStateException("The service already has a city in its database. Wipe it first:"
                    + " docker compose down -v, then docker compose up -d, then restart the service.");
        }
        for (Facility f : plan.facilities()) {
            created("/api/facilities", facilityBody(f));
        }
        for (Resource u : plan.units()) {
            created("/api/units", unitBody(u));
        }
        for (Patient p : plan.patients()) {
            created("/api/patients", patientBody(p));
        }
    }

    /** Starts listening to the live feed and the timers. */
    void start() {
        feed.start();
        timers.scheduleWithFixedDelay(() -> flushPositions(), POSITION_FLUSH_MS, POSITION_FLUSH_MS,
                TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(() -> onSender(() -> finishOnScene()), ON_SCENE_CHECK_MS, ON_SCENE_CHECK_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Stops every thread and closes the connections. */
    void stop() {
        stopping = true;                                      // requests cut off from here on are not failures
        feed.stop();
        timers.shutdownNow();
        sender.shutdownNow();
        service.close();
    }

    long readings()      { return readings.get(); }
    long tripsSeen()     { return tripsSeen.get(); }
    long tripsFinished() { return tripsFinished.get(); }
    long conflicts()     { return conflicts.get(); }
    long errors()        { return errors.get(); }

    /**
     * Counts a failure and prints the first few, so a broken run is visible without flooding the console.
     *
     * @param what what went wrong
     */
    void error(String what) {
        if (stopping) {
            return;                                           // a request interrupted by stop() did not fail
        }
        long n = errors.incrementAndGet();
        if (n <= ERRORS_PRINTED) {
            System.err.println("[http] " + what + (n == ERRORS_PRINTED ? " (further errors are only counted)" : ""));
        }
    }

    // ---------- CityLink: from the agents' threads; each only queues work ----------

    @Override
    public void reading(VitalsReading reading) {
        VitalsReading now = retimed(reading, Instant.now());
        onSender(() -> {
            if (call(service.post("/api/readings", now), "reading")) {
                readings.incrementAndGet();
            }
        });
    }

    @Override
    public void answered(String assignmentId) {
        onSender(() -> call(service.post("/api/trips/" + assignmentId + "/accept", null), "accept"));
    }

    @Override
    public void position(String unitId, Location where) {
        pendingPositions.put(unitId, where);                  // only the latest one per unit is kept
    }

    @Override
    public void arrived(String assignmentId) {
        onSender(() -> {
            if (!call(service.post("/api/trips/" + assignmentId + "/arrive", null), "arrive")) {
                return;
            }
            Change trip = trips.get(assignmentId);
            if (trip != null && trip.role() == AssignmentRole.PRIMARY) {
                LivePatient patient = patients.get(trip.patientId());
                if (patient != null) {
                    patient.treat();                          // the ambulance takes over care
                }
                onSceneSince.put(assignmentId, clock.instant());
            }
        });
    }

    @Override
    public void backInService(String unitId) {
        onSender(() -> call(service.post("/api/units/" + unitId + "/back-in-service", null), "back in service"));
    }

    // ---------- the live feed: on the live-feed thread ----------

    private void onSnapshot(String data) {
        List<Change> changes;
        try {
            changes = watcher.update(service.parse(data));
        } catch (RuntimeException e) {
            error("unreadable live picture: " + e.getMessage());
            return;
        }
        if (!changes.isEmpty()) {
            onSender(() -> apply(changes));
        }
    }

    // ---------- on the sender thread ----------

    private void apply(List<Change> changes) {
        for (Change c : changes) {
            LiveUnit unit = units.get(c.unitId());
            if (unit == null) {
                continue;                                     // not one of this city's units
            }
            switch (c.kind()) {
                case NEW -> {
                    if (c.target() == null) {
                        error("trip " + c.tripId() + " has no open case in the live picture");
                        continue;
                    }
                    trips.put(c.tripId(), c);
                    tripsSeen.incrementAndGet();
                    unit.order(new TripOrder(TripOrder.Kind.START, c.tripId(), c.target(), c.role(), null));
                }
                case ROLE -> {
                    Change before = trips.get(c.tripId());
                    if (before != null) {
                        trips.put(c.tripId(), new Change(before.kind(), before.tripId(), before.unitId(),
                                before.caseId(), before.patientId(), c.role(), before.target()));
                    }
                    unit.order(new TripOrder(TripOrder.Kind.ROLE, c.tripId(), null, c.role(), null));
                }
                case GONE -> {
                    trips.remove(c.tripId());
                    onSceneSince.remove(c.tripId());
                    unit.order(new TripOrder(TripOrder.Kind.END, c.tripId(), null, null, finalStatus(c.tripId())));
                }
            }
        }
    }

    // how an ended trip ended: TIMED_OUT and FAILED send the unit for repair
    private AssignmentStatus finalStatus(String tripId) {
        ServiceClient.Response r = service.get("/api/trips/" + tripId);
        if (!r.ok() || r.body() == null) {
            error("GET /api/trips/" + tripId + " answered " + r.status());
            return AssignmentStatus.CANCELLED;                // unknown: just free the unit
        }
        return AssignmentStatus.valueOf(ServiceClient.text(r.body(), "status"));
    }

    // crews whose time on scene is up finish the job
    // (S2: serious cases are taken to hospital instead)
    private void finishOnScene() {
        Instant now = clock.instant();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Instant> e : onSceneSince.entrySet()) {
            if (!now.isBefore(e.getValue().plus(Simulation.ON_SCENE))) {
                due.add(e.getKey());
            }
        }
        for (String tripId : due) {
            onSceneSince.remove(tripId);
            if (call(service.post("/api/trips/" + tripId + "/complete", null), "complete")) {
                tripsFinished.incrementAndGet();
            }
        }
    }

    private void flushPositions() {
        for (String unitId : pendingPositions.keySet()) {
            Location where = pendingPositions.remove(unitId);
            if (where != null) {
                onSender(() -> call(service.put("/api/units/" + unitId + "/position", coordinates(where)), "position"));
            }
        }
    }

    // ---------- helpers ----------

    /**
     * @return {@code true} if the service accepted the request; a 409 or another refusal is counted
     */
    private boolean call(ServiceClient.Response r, String what) {
        if (r.ok()) {
            return true;
        }
        if (r.status() == 409) {
            conflicts.incrementAndGet();
        } else {
            error(what + " answered " + r.status() + (r.body() == null ? "" : ": " + ServiceClient.text(r.body(), "detail")));
        }
        return false;
    }

    private void created(String path, Map<String, Object> body) {
        ServiceClient.Response r = service.post(path, body);
        if (!r.ok()) {
            throw new IllegalStateException("POST " + path + " answered " + r.status()
                    + (r.body() == null ? "" : ": " + ServiceClient.text(r.body(), "detail")));
        }
    }

    // queues work for the sender thread; a failure is counted, not thrown; once stopped, work is dropped
    private void onSender(Runnable work) {
        try {
            sender.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    error(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException stopping) {
            // the city is shutting down
        }
    }

    /**
     * The same reading, stamped with the service's kind of time (real time) instead of the city's sped-up time.
     *
     * @param reading the reading as the wearable made it
     * @param now     the real time to stamp it with
     * @return a copy with only the time changed
     */
    static VitalsReading retimed(VitalsReading reading, Instant now) {
        return new VitalsReading(reading.patientId(), now, reading.heartRate(), reading.spo2(), reading.systolicBp(),
                reading.diastolicBp(), reading.respRate(), reading.temperature(), reading.consciousness());
    }

    private static Map<String, Object> coordinates(Location where) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", where.latitude());
        body.put("longitude", where.longitude());
        return body;
    }

    private static Map<String, Object> contact(Contact c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", c.name());
        body.put("phone", c.phone());
        return body;
    }

    private static Map<String, Object> facilityBody(Facility f) {
        List<String> capabilities = new ArrayList<>();
        for (Capability c : f.capabilities()) {
            capabilities.add(c.name());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", f.id());
        body.put("name", f.name());
        body.put("kind", f.kind().name());
        body.put("position", coordinates(f.location()));
        body.put("capabilities", capabilities);
        body.put("accepting", f.accepting());
        body.put("phone", f.phone());
        return body;
    }

    private static Map<String, Object> unitBody(Resource u) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", u.id());
        body.put("type", u.type().name());
        body.put("position", coordinates(u.location()));
        body.put("baseId", u.baseId().orElseThrow());
        body.put("driver", contact(u.driver().orElseThrow()));
        return body;
    }

    private static Map<String, Object> patientBody(Patient p) {
        PersonDetails who = p.details().orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("home", coordinates(p.location()));
        body.put("name", who.name());
        body.put("age", who.age());
        body.put("sex", who.sex().name());
        if (p.emergencyContact().isPresent()) {
            body.put("emergencyContact", contact(p.emergencyContact().get()));
        }
        return body;
    }

    private static Thread daemon(Runnable work, String name) {
        Thread t = new Thread(work, name);
        t.setDaemon(true);                                    // never keeps the program alive on its own
        return t;
    }
}

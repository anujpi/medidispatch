package com.medidispatch.sim;

import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Bangalore city ({@link BangaloreCity}) that lives against the RUNNING dispatch service: the same patients and
 * crews as {@link LiveSimulation}, but every reading, answer, position and arrival goes to the service over HTTP
 * ({@link HttpLink}), and the service does all the deciding and stores everything in its database.
 * <p>
 * Use: {@link #setUp()} (registers the city; needs an empty database), then {@link #start()}, later {@link #stop()}.
 */
public final class HttpSimulation {

    private static final int AGENT_THREADS = 4;

    private final ScaledClock clock;
    private final HttpLink link;
    private final BangaloreCity.Plan plan;
    private final List<Runnable> agents = new ArrayList<>();
    private final ScheduledExecutorService agentPool;
    private boolean started;

    /**
     * Builds the city. Nothing is sent and nothing moves yet.
     *
     * @param config  the counts, failure chances, seed and speed ({@link LiveConfig#bangalore()} is a good start)
     * @param service where the service runs, for example {@code http://localhost:8080}
     * @throws IllegalArgumentException if an argument is missing
     */
    public HttpSimulation(LiveConfig config, URI service) {
        if (config == null || service == null) {
            throw new IllegalArgumentException("config and service are required");
        }
        clock = new ScaledClock(LiveSimulation.START, config.startSpeed());
        clock.pause();                                        // frozen until start
        link = new HttpLink(new ServiceClient(service), clock);
        plan = BangaloreCity.plan(config);

        Random dice = new Random(config.seed() + 1);          // the plan used the seed itself
        Map<String, LivePatient> patients = new LinkedHashMap<>();
        for (Patient p : plan.patients()) {
            LivePatient patient = new LivePatient(p.id(), p.location(), clock, link, new Random(dice.nextLong()),
                    config.meanHoursBetweenIllnesses());
            patients.put(p.id(), patient);
            agents.add(patient);
        }
        Map<String, LiveUnit> units = new LinkedHashMap<>();
        for (Resource r : plan.units()) {
            // each unit starts at its base, which is also its home to drive back to after a trip
            LiveUnit unit = new LiveUnit(new SimulatedUnit(r.id(), r.type(), r.location(), r.location()), clock,
                    link, new Random(dice.nextLong()), config);
            units.put(r.id(), unit);
            agents.add(unit);
        }
        link.connect(patients, units, plan, new Random(dice.nextLong()));

        AtomicInteger threadNumber = new AtomicInteger();
        agentPool = Executors.newScheduledThreadPool(AGENT_THREADS, (Runnable work) -> {
            Thread t = new Thread(work, "http-agents-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers the city's hospitals, clinics, units and patients in the service.
     *
     * @throws IllegalStateException             if the service's database is not empty, or it refuses something
     * @throws java.io.UncheckedIOException      if the service cannot be reached
     */
    public void setUp() {
        link.setUp(plan);
    }

    /**
     * Lets the city live: simulated time runs, patients fall ill, crews answer and drive.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("the simulation has already been started");
        }
        started = true;
        clock.resume();
        link.start();
        long beat = LiveEngine.BEAT.toMillis();
        Random spread = new Random();
        for (Runnable agent : agents) {
            // a random first delay, so the agents do not all wake up at the same moment
            agentPool.scheduleWithFixedDelay(guarded(agent), spread.nextInt((int) beat), beat, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Changes how fast the city lives, from now on. What is sent to the service still carries real time.
     *
     * @param speed simulated seconds per real second, 1 to {@link LiveSimulation#MAX_SPEED}
     * @throws IllegalArgumentException if the speed is out of range
     */
    public void setSpeed(int speed) {
        if (speed < 1 || speed > LiveSimulation.MAX_SPEED) {
            throw new IllegalArgumentException("speed must be 1 to " + LiveSimulation.MAX_SPEED + ", got " + speed);
        }
        clock.setSpeed(speed);
    }

    /**
     * Freezes the city's time: nobody falls ill, nothing moves. The SERVICE keeps running in real time, so a crew on a
     * trip that stops reporting for about 30 s is timed out by it (its signal check), as it would be for real.
     */
    public void pause() {
        clock.pause();
    }

    /** Lets the city's time run again. */
    public void resume() {
        clock.resume();
    }

    /** @return simulated seconds per real second */
    public int speed()       { return clock.speed(); }

    /** @return {@code true} while paused */
    public boolean paused()  { return clock.paused(); }

    /** Stops every thread. The simulation cannot be restarted. */
    public synchronized void stop() {
        agentPool.shutdownNow();
        link.stop();
    }

    /**
     * @return one line: simulated time so far, the speed (or PAUSED), readings sent, trips seen, finished and taken to hospital, 409s and
     *         errors
     */
    public String status() {
        Duration simulated = Duration.between(LiveSimulation.START, clock.instant());
        return String.format(
                "sim +%dh%02dm %s | readings %d | trips %d seen, %d finished, %d to hospital | 409s %d | errors %d",
                simulated.toHours(), simulated.toMinutesPart(),
                clock.paused() ? "PAUSED" : "x" + clock.speed(), link.readings(), link.tripsSeen(),
                link.tripsFinished(), link.transported(), link.conflicts(), link.errors());
    }

    /** @return readings the service accepted */
    public long readingsSent()  { return link.readings(); }

    /** @return trips the crews completed */
    public long tripsFinished() { return link.tripsFinished(); }

    /** @return patients the crews took to hospital and handed over */
    public long transported()   { return link.transported(); }

    /** @return failed requests other than 409s; should stay 0 */
    public long errors()        { return link.errors(); }

    // an agent that throws would be silently cancelled by its executor, so failures are counted instead
    private Runnable guarded(Runnable agent) {
        return () -> {
            try {
                agent.run();
            } catch (RuntimeException e) {
                link.error("agent failed: " + e);
            }
        };
    }
}

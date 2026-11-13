package com.medidispatch.sim;

import com.medidispatch.core.domain.ResourceType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An endless, random city running on the real engine, on several threads, in (sped-up) real time.
 * <p>
 * Where {@link Simulation} is a planned, seeded, step-by-step run, this one just lives: patients fall ill at
 * random moments, units misbehave at random, failed units are repaired and come back, and it goes on until
 * {@link #stop()}.
 * <p>
 * The threads:
 * <ul>
 *   <li>one <b>engine</b> thread ({@link LiveEngine}) owns the dispatch engine and is the only one that touches it;</li>
 *   <li>a small pool of <b>agent</b> threads runs every patient ({@link LivePatient}) and unit ({@link LiveUnit})
 *       as a repeating task; they only send messages to the engine and receive trip orders from it;</li>
 *   <li>whoever shows the city (the window's UI thread) reads {@link #latestSnapshot()}, an immutable picture
 *       the engine republishes every few milliseconds.</li>
 * </ul>
 * All threads are daemon threads, so a forgotten simulation never stops the program from exiting.
 */
public final class LiveSimulation {

    /** The fastest allowed speed (simulated seconds per real second). Faster, and units' timings get too coarse. */
    public static final int MAX_SPEED = 300;

    /** Simulated time when every live run starts. */
    static final Instant START = Instant.parse("2026-01-05T08:00:00Z");

    private static final int AGENT_THREADS = 4;

    private final ScaledClock clock;
    private final LiveEngine engine;
    private final List<Runnable> agents = new ArrayList<>();
    private final ScheduledExecutorService agentPool;
    private boolean started;

    /**
     * Builds the city and the engine. Nothing moves until {@link #start()}; simulated time does not pass either.
     *
     * @param config the city, its people and units, and how often things go wrong
     * @throws IllegalArgumentException if {@code config} is missing
     */
    public LiveSimulation(LiveConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        clock = new ScaledClock(START, config.startSpeed());
        clock.pause();                                        // frozen until start
        engine = new LiveEngine(clock, config);

        Random layout = new Random(config.seed());
        for (int i = 1; i <= config.patientCount(); i++) {
            LivePatient p = new LivePatient(String.format("p%03d", i), CityMap.randomPoint(layout, config.citySizeKm()),
                    clock, engine, new Random(layout.nextLong()), config.meanHoursBetweenIllnesses());
            engine.addPatient(p);
            agents.add(p);
        }
        addUnits("als", ResourceType.AMBULANCE_ALS, config.alsCount(), config, layout);
        addUnits("bls", ResourceType.AMBULANCE_BLS, config.blsCount(), config, layout);
        addUnits("nurse", ResourceType.NURSE, config.nurseCount(), config, layout);
        addUnits("doctor", ResourceType.DOCTOR, config.doctorCount(), config, layout);
        engine.publishFirstSnapshot();

        AtomicInteger threadNumber = new AtomicInteger();
        agentPool = Executors.newScheduledThreadPool(AGENT_THREADS, (Runnable work) -> {
            Thread t = new Thread(work, "agents-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the threads and lets simulated time run.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("the simulation has already been started");
        }
        started = true;
        clock.resume();
        engine.start();
        long beat = LiveEngine.BEAT.toMillis();
        Random spread = new Random();
        for (Runnable agent : agents) {
            // a random first delay, so the agents do not all wake up at the same moment
            agentPool.scheduleWithFixedDelay(engine.guarded(agent), spread.nextInt((int) beat), beat,
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Stops every thread and waits briefly for them to finish. The simulation cannot be restarted. */
    public synchronized void stop() {
        agentPool.shutdownNow();
        try {
            agentPool.awaitTermination(2, TimeUnit.SECONDS);
            engine.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();               // keep the interrupt for whoever called us
        }
    }

    /** Freezes simulated time: nothing moves, nobody falls ill, no timeouts fire. */
    public void pause() {
        clock.pause();
    }

    /** Lets simulated time run again. */
    public void resume() {
        clock.resume();
    }

    /**
     * Changes how fast simulated time runs.
     *
     * @param speed simulated seconds per real second, 1 to {@link #MAX_SPEED}
     * @throws IllegalArgumentException if the speed is out of range
     */
    public void setSpeed(int speed) {
        if (speed < 1 || speed > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be 1 to " + MAX_SPEED + ", got " + speed);
        }
        clock.setSpeed(speed);
    }

    public int speed()       { return clock.speed(); }
    public boolean paused()  { return clock.paused(); }
    public Instant startTime() { return START; }

    /**
     * The latest picture of the city. Safe to call from any thread, as often as wanted; it never blocks.
     *
     * @return the newest snapshot published by the engine thread
     */
    public WorldSnapshot latestSnapshot() {
        return engine.latestSnapshot();
    }

    // for tests
    LiveEngine engine() {
        return engine;
    }

    boolean threadsStopped() {
        return agentPool.isTerminated() && engine.stopped();
    }

    private void addUnits(String prefix, ResourceType type, int count, LiveConfig config, Random layout) {
        for (int i = 1; i <= count; i++) {
            SimulatedUnit moving = new SimulatedUnit(prefix + "-" + i, type,
                    CityMap.randomPoint(layout, config.citySizeKm()));
            LiveUnit unit = new LiveUnit(moving, clock, engine, new Random(layout.nextLong()), config);
            engine.addUnit(unit);
            agents.add(unit);
        }
    }
}

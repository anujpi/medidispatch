package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * A patient in the live simulation: a wearable that sends a reading every simulated minute, and a person who falls
 * ill at random moments, gets treated, recovers, and may fall ill again later.
 * <p>
 * It runs as a repeating task on the agent thread pool ({@link #run()}), and the engine thread calls
 * {@link #treat()} when an ambulance arrives. Both are {@code synchronized}, so the two threads never change it at
 * the same time. It never touches the engine directly: it only hands readings to its {@link CityLink} (the engine in
 * memory, or the running service over HTTP).
 * <p>
 * Illnesses arrive at random (a Poisson process): the wait until the next one is drawn from an exponential
 * distribution, so most waits are short-ish, a few are long, and nobody can predict the next one.
 */
final class LivePatient implements Runnable {

    /** After treatment, a patient stays healthy at least this long before they can fall ill again. */
    static final Duration RECOVERY = Duration.ofHours(2);

    private final String id;
    private final Location home;
    private final ScaledClock clock;
    private final CityLink link;
    private final Random dice;
    private final double meanHoursBetweenIllnesses;

    private SimulatedPatient episode;         // the current illness, or a healthy placeholder
    private boolean ill;
    private Instant nextIllnessAt;
    private Instant nextReadingAt;

    /**
     * Creates a healthy patient whose first illness and first reading are at random moments.
     *
     * @param id                         patient id, also used in the engine
     * @param home                       where the patient is
     * @param clock                      the live simulation's clock
     * @param link                       where readings are sent
     * @param dice                       this patient's own randomness
     * @param meanHoursBetweenIllnesses  average time between two illnesses
     */
    LivePatient(String id, Location home, ScaledClock clock, CityLink link, Random dice,
                double meanHoursBetweenIllnesses) {
        this.id = id;
        this.home = home;
        this.clock = clock;
        this.link = link;
        this.dice = dice;
        this.meanHoursBetweenIllnesses = meanHoursBetweenIllnesses;
        Instant now = clock.instant();
        this.episode = new SimulatedPatient(id, home, null, 0);
        this.nextIllnessAt = now.plus(randomWait());
        // spread the first readings over the first minute, so 200 wearables do not all report at once
        this.nextReadingAt = now.plusMillis((long) (dice.nextDouble() * Simulation.READING_INTERVAL.toMillis()));
    }

    /** One beat of the wearable: maybe fall ill, and send a reading when one is due. Runs on the agent pool. */
    @Override
    public synchronized void run() {
        Instant now = clock.instant();
        if (!ill && !now.isBefore(nextIllnessAt)) {
            ill = true;
            episode = new SimulatedPatient(id, home, now, randomPeak());
        }
        if (!now.isBefore(nextReadingAt)) {
            link.reading(episode.readingAt(now));
            nextReadingAt = now.plus(Simulation.READING_INTERVAL);
        }
    }

    /** Help has arrived: healthy from now on, until a new illness some time after {@link #RECOVERY}. */
    synchronized void treat() {
        episode.treat();
        ill = false;
        nextIllnessAt = clock.instant().plus(RECOVERY).plus(randomWait());
    }

    String id()       { return id; }
    Location home()   { return home; }               // never changes, so safe to read from any thread

    // an exponential wait: the time until the next random event when events happen at a steady average rate
    private Duration randomWait() {
        if (Double.isInfinite(meanHoursBetweenIllnesses)) {
            return Duration.ofDays(365_000);                  // effectively never
        }
        double hours = -Math.log(1 - dice.nextDouble()) * meanHoursBetweenIllnesses;
        return Duration.ofSeconds((long) (hours * 3600));
    }

    // 30% end up MEDIUM, 30% HIGH, 40% CRITICAL, as in the step-by-step simulation
    private double randomPeak() {
        double level = dice.nextDouble();
        return level < 0.3 ? SimulatedPatient.MEDIUM_PEAK
             : level < 0.6 ? SimulatedPatient.HIGH_PEAK
             : SimulatedPatient.CRITICAL_PEAK;
    }
}

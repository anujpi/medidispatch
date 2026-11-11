package com.medidispatch.sim;

import java.time.Duration;

/**
 * Everything that shapes one simulated run.
 * <p>
 * The same config, seed included, always builds the same city: the same patients in the same places, the same
 * people falling ill at the same moments, and the same units misbehaving on the same trips.
 *
 * @param seed                where all randomness starts; the same seed gives the same world
 * @param duration            how much simulated time to run
 * @param tick                how much simulated time one step covers; smaller than {@code duration}
 * @param patientCount        how many monitored patients live in the city
 * @param deteriorationChance share of patients (0 to 1) who fall ill at some point during the run
 * @param alsCount            advanced (ALS) ambulances
 * @param blsCount            basic (BLS) ambulances
 * @param nurseCount          nurses (sent for MEDIUM cases and as first responders)
 * @param doctorCount         doctors (sent as first responders)
 * @param noAckChance         chance (0 to 1) that a unit never answers a given trip
 * @param breakdownChance     chance (0 to 1) that a unit breaks down halfway through a given trip
 * @param signalLossChance    chance (0 to 1) that a unit loses its signal halfway through a given trip
 */
public record SimulationConfig(long seed,
                               Duration duration,
                               Duration tick,
                               int patientCount,
                               double deteriorationChance,
                               int alsCount,
                               int blsCount,
                               int nurseCount,
                               int doctorCount,
                               double noAckChance,
                               double breakdownChance,
                               double signalLossChance) {

    /**
     * Validates the config.
     *
     * @throws IllegalArgumentException if a duration is missing or not positive, the tick is not smaller than the
     *                                  duration, a count is negative, a chance is outside 0 to 1, or the three
     *                                  failure chances add up to more than 1
     */
    public SimulationConfig {
        if (duration == null || tick == null) {
            throw new IllegalArgumentException("duration and tick are required");
        }
        if (duration.isZero() || duration.isNegative() || tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("duration and tick must be positive");
        }
        if (tick.compareTo(duration) >= 0) {
            throw new IllegalArgumentException("tick must be smaller than duration");
        }
        if (patientCount < 0 || alsCount < 0 || blsCount < 0 || nurseCount < 0 || doctorCount < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
        checkChance(deteriorationChance, "deteriorationChance");
        checkChance(noAckChance, "noAckChance");
        checkChance(breakdownChance, "breakdownChance");
        checkChance(signalLossChance, "signalLossChance");
        if (noAckChance + breakdownChance + signalLossChance > 1) {
            throw new IllegalArgumentException("the three failure chances cannot add up to more than 1");
        }
    }

    /**
     * A sensible city: 50 patients (30% fall ill), 4 ALS, 6 BLS, 6 nurses, 2 doctors, a 5% chance of each kind of
     * unit failure, two hours in 10-second steps.
     *
     * @return the default config, seed 42
     */
    public static SimulationConfig defaults() {
        return new SimulationConfig(42, Duration.ofHours(2), Duration.ofSeconds(10), 50, 0.3,
                4, 6, 6, 2, 0.05, 0.05, 0.05);
    }

    /**
     * The same config with different failure chances.
     *
     * @param noAck      chance a unit never answers a trip
     * @param breakdown  chance a unit breaks down halfway
     * @param signalLoss chance a unit loses its signal halfway
     * @return a copy with the new chances
     */
    public SimulationConfig withFailures(double noAck, double breakdown, double signalLoss) {
        return new SimulationConfig(seed, duration, tick, patientCount, deteriorationChance,
                alsCount, blsCount, nurseCount, doctorCount, noAck, breakdown, signalLoss);
    }

    private static void checkChance(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, got " + value);
        }
    }
}

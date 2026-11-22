package com.medidispatch.sim;

import java.time.Duration;
import java.util.Random;

/**
 * Everything that shapes a live, endless simulation.
 * <p>
 * Unlike {@link SimulationConfig}, nothing about the run is planned: patients fall ill at random moments (on
 * average {@code illnessesPerHour} across the city), units break down at random, and it never ends. The seed only
 * decides where patients and units are placed; with threads, the rest is different every time.
 *
 * @param seed              where the city layout's randomness starts
 * @param citySizeKm        the city's width and height in kilometres
 * @param patientCount      monitored patients in the city
 * @param alsCount          advanced (ALS) ambulances
 * @param blsCount          basic (BLS) ambulances
 * @param nurseCount        nurses
 * @param doctorCount       doctors
 * @param illnessesPerHour  how many patients fall ill per simulated hour across the city, on average
 * @param noAckChance       chance (0 to 1) that a unit never answers a given trip
 * @param breakdownChance   chance (0 to 1) that a unit breaks down halfway through a given trip
 * @param signalLossChance  chance (0 to 1) that a unit loses its signal halfway through a given trip
 * @param repairTime        how long a failed unit is out of service before it is back
 * @param startSpeed        simulated seconds per real second at the start, 1 to {@link LiveSimulation#MAX_SPEED}
 */
public record LiveConfig(long seed,
                         double citySizeKm,
                         int patientCount,
                         int alsCount,
                         int blsCount,
                         int nurseCount,
                         int doctorCount,
                         double illnessesPerHour,
                         double noAckChance,
                         double breakdownChance,
                         double signalLossChance,
                         Duration repairTime,
                         int startSpeed) {

    /**
     * Validates the config.
     *
     * @throws IllegalArgumentException if a size, count, rate, chance, repair time or speed is out of range
     */
    public LiveConfig {
        if (citySizeKm <= 0) {
            throw new IllegalArgumentException("citySizeKm must be positive, got " + citySizeKm);
        }
        if (patientCount < 0 || alsCount < 0 || blsCount < 0 || nurseCount < 0 || doctorCount < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
        if (illnessesPerHour < 0) {
            throw new IllegalArgumentException("illnessesPerHour cannot be negative, got " + illnessesPerHour);
        }
        if (noAckChance < 0 || breakdownChance < 0 || signalLossChance < 0
                || noAckChance + breakdownChance + signalLossChance > 1) {
            throw new IllegalArgumentException("failure chances must be at least 0 and add up to at most 1");
        }
        if (repairTime == null || repairTime.isNegative() || repairTime.isZero()) {
            throw new IllegalArgumentException("repairTime must be positive");
        }
        if (startSpeed < 1 || startSpeed > LiveSimulation.MAX_SPEED) {
            throw new IllegalArgumentException("startSpeed must be 1 to " + LiveSimulation.MAX_SPEED + ", got " + startSpeed);
        }
    }

    /**
     * A big city with a new random layout every time: 40 by 40 km, 200 patients, 16 ALS, 24 BLS, 20 nurses and
     * 8 doctors, about 8 illnesses an hour, a 5% chance of each kind of unit failure, 20-minute repairs, speed 60.
     *
     * @return the default live config
     */
    public static LiveConfig bigCity() {
        return new LiveConfig(new Random().nextLong(), 40, 200, 16, 24, 20, 8, 8, 0.05, 0.05, 0.05,
                Duration.ofMinutes(20), 60);
    }

    /**
     * The Bangalore city driven over HTTP ({@link HttpSimulation}): 60 patients, 6 ALS, 6 BLS, 5 nurses and 3 doctors
     * (units are based at {@link BangaloreCity}'s hospitals and clinics), about 8 illnesses an hour, a 5% chance of
     * each kind of unit failure, 20-minute repairs, speed 10. The city size is not used: the city's edges come from
     * {@link BangaloreCity}.
     *
     * @return the default config for the simulator over HTTP
     */
    public static LiveConfig bangalore() {
        return new LiveConfig(new Random().nextLong(), 30, 60, 6, 6, 5, 3, 8, 0.05, 0.05, 0.05,
                Duration.ofMinutes(20), 10);
    }

    /**
     * @param speed simulated seconds per real second, 1 to {@link LiveSimulation#MAX_SPEED}
     * @return a copy of this config with only the start speed changed
     * @throws IllegalArgumentException if the speed is out of range
     */
    public LiveConfig withStartSpeed(int speed) {
        return new LiveConfig(seed, citySizeKm, patientCount, alsCount, blsCount, nurseCount, doctorCount,
                illnessesPerHour, noAckChance, breakdownChance, signalLossChance, repairTime, speed);
    }

    /**
     * How far from a patient units are looked for: at least 20 km, more in a bigger city.
     *
     * @return the search radius in kilometres
     */
    public double searchRadiusKm() {
        return Math.max(20, citySizeKm * 0.6);
    }

    /**
     * The average time between two illnesses of the same patient, so the whole city sees about
     * {@code illnessesPerHour} an hour.
     *
     * @return the mean, in hours; infinite if nobody ever falls ill
     */
    public double meanHoursBetweenIllnesses() {
        return illnessesPerHour == 0 ? Double.POSITIVE_INFINITY : patientCount / illnessesPerHour;
    }
}

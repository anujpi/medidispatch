package com.medidispatch.sim;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class SimulationConfigTest {

    private SimulationConfig with(Duration duration, Duration tick, int patients, double illChance,
                                  int als, double noAck, double breakdown, double signalLoss) {
        return new SimulationConfig(1, duration, tick, patients, illChance, als, 1, 1, 1, noAck, breakdown, signalLoss);
    }

    @Test
    void theDefaultsAreValid() {
        SimulationConfig config = SimulationConfig.defaults();

        assertEquals(42, config.seed());
        assertEquals(Duration.ofHours(2), config.duration());
        assertEquals(50, config.patientCount());
    }

    @Test
    void withFailuresChangesOnlyTheFailureChances() {
        SimulationConfig config = SimulationConfig.defaults().withFailures(0.1, 0.2, 0.3);

        assertEquals(0.1, config.noAckChance());
        assertEquals(0.2, config.breakdownChance());
        assertEquals(0.3, config.signalLossChance());
        assertEquals(SimulationConfig.defaults().patientCount(), config.patientCount());
        assertEquals(SimulationConfig.defaults().seed(), config.seed());
    }

    @Test
    void durationsMustBePresentPositiveAndTheTickSmaller() {
        Duration hour = Duration.ofHours(1);
        Duration tenSeconds = Duration.ofSeconds(10);

        assertThrows(IllegalArgumentException.class, () -> with(null, tenSeconds, 1, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(hour, null, 1, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(Duration.ZERO, tenSeconds, 1, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(hour, Duration.ofSeconds(-1), 1, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(hour, hour, 1, 0, 1, 0, 0, 0));
    }

    @Test
    void countsCannotBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> with(Duration.ofHours(1), Duration.ofSeconds(10), -1, 0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> with(Duration.ofHours(1), Duration.ofSeconds(10), 1, 0, -1, 0, 0, 0));
    }

    @Test
    void chancesMustBeBetweenZeroAndOneAndFailuresAtMostOneInTotal() {
        Duration hour = Duration.ofHours(1);
        Duration tick = Duration.ofSeconds(10);

        assertThrows(IllegalArgumentException.class, () -> with(hour, tick, 1, 1.5, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(hour, tick, 1, 0, 1, -0.1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> with(hour, tick, 1, 0, 1, 0.5, 0.4, 0.2));
        assertDoesNotThrow(() -> with(hour, tick, 1, 1, 1, 0.5, 0.3, 0.2));   // exactly 1 is fine
    }
}

package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

public class TrafficProfileTest {

    private final TrafficProfile weekday = TrafficProfile.bangaloreWeekday();

    @Test
    void theRoadsAreFreeAtNight() {
        for (RoadClass c : RoadClass.values()) {
            assertEquals(1.0, weekday.factor(c, LocalTime.of(3, 0)), 1e-12, c.name());
        }
    }

    /** The evening peak is the worst moment for main roads. */
    @Test
    void sixInTheEveningIsTheSlowestForMainRoads() {
        double peak = weekday.factor(RoadClass.PRIMARY, LocalTime.of(18, 0));
        assertEquals(0.4, peak, 1e-12);
        for (int minute = 0; minute < 24 * 60; minute += 15) {
            assertTrue(weekday.factor(RoadClass.PRIMARY, LocalTime.of(minute / 60, minute % 60)) >= peak - 1e-12);
        }
    }

    /** 9:30 lies halfway between 9:00 (0.45) and 10:00 (0.5); 23:30 blends back into midnight. */
    @Test
    void factorsAreInterpolatedBetweenHours() {
        assertEquals(0.475, weekday.factor(RoadClass.PRIMARY, LocalTime.of(9, 30)), 1e-12);
        assertEquals(1.0, weekday.factor(RoadClass.PRIMARY, LocalTime.of(23, 30)), 1e-12);
        assertEquals(0.95, weekday.factor(RoadClass.PRIMARY, LocalTime.of(22, 30)), 1e-12);
    }

    /** Traffic piles onto main roads: a lane slows less than a primary road at the same moment. */
    @Test
    void smallerRoadsSlowLessThanMainRoads() {
        LocalTime peak = LocalTime.of(18, 0);

        assertEquals(0.4, weekday.factor(RoadClass.PRIMARY, peak), 1e-12);
        assertEquals(1 - 0.6 * 0.6, weekday.factor(RoadClass.TERTIARY, peak), 1e-12);
        assertEquals(1 - 0.6 * 0.3, weekday.factor(RoadClass.RESIDENTIAL, peak), 1e-12);
        assertTrue(weekday.factor(RoadClass.RESIDENTIAL, peak) > weekday.factor(RoadClass.TERTIARY, peak));
    }

    @Test
    void everyFactorIsAboveZeroAndAtMostOne() {
        for (RoadClass c : RoadClass.values()) {
            for (int minute = 0; minute < 24 * 60; minute += 7) {
                double f = weekday.factor(c, LocalTime.of(minute / 60, minute % 60));
                assertTrue(f > 0 && f <= 1, c + " at minute " + minute + ": " + f);
            }
        }
    }

    @Test
    void aBadCurveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficProfile(new double[23]));
        assertThrows(IllegalArgumentException.class, () -> new TrafficProfile(null));
        double[] withZero = new double[24];
        java.util.Arrays.fill(withZero, 1.0);
        withZero[8] = 0;
        assertThrows(IllegalArgumentException.class, () -> new TrafficProfile(withZero));
    }
}

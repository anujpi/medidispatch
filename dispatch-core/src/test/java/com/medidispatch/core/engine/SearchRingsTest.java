package com.medidispatch.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SearchRingsTest {

    private static final double DELTA = 1e-9;

    @Test
    void theFirstRingIsTwoKilometres() {
        assertEquals(2.0, SearchRings.first(20.0), DELTA);
    }

    @Test
    void theFirstRingNeverExceedsASmallerMaximum() {
        assertEquals(1.5, SearchRings.first(1.5), DELTA);
    }

    @Test
    void eachRingDoublesTheLastOneButStopsAtTheMaximum() {
        assertEquals(4.0, SearchRings.next(2.0, 20.0), DELTA);
        assertEquals(8.0, SearchRings.next(4.0, 20.0), DELTA);
        assertEquals(16.0, SearchRings.next(8.0, 20.0), DELTA);
        assertEquals(20.0, SearchRings.next(16.0, 20.0), DELTA);   // 32 would pass the maximum
        assertEquals(20.0, SearchRings.next(20.0, 20.0), DELTA);   // already at the maximum
    }

    @Test
    void walkingTheRingsReachesTheMaximumInAFewSteps() {
        double radius = SearchRings.first(20.0);
        int steps = 1;
        while (radius < 20.0) {
            radius = SearchRings.next(radius, 20.0);
            steps++;
        }
        assertEquals(5, steps);                                    // 2, 4, 8, 16, 20
        assertEquals(20.0, radius, DELTA);
    }
}

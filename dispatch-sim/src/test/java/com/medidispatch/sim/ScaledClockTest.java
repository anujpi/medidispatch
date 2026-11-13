package com.medidispatch.sim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The sped-up clock, driven by a fake source of real time so nothing depends on the machine's speed. */
public class ScaledClockTest {

    private static final Instant START = Instant.parse("2026-01-05T08:00:00Z");
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong realNanos = new AtomicLong(5 * SECOND);       // any starting value works
    private final ScaledClock clock = new ScaledClock(START, 60, () -> realNanos.get());

    @Test
    void oneRealSecondAtSpeedSixtyIsOneSimulatedMinute() {
        realNanos.addAndGet(SECOND);
        assertEquals(START.plusSeconds(60), clock.instant());
    }

    @Test
    void changingSpeedKeepsTheTimeSoFar() {
        realNanos.addAndGet(SECOND);                                        // 60 s simulated at speed 60
        clock.setSpeed(10);
        realNanos.addAndGet(2 * SECOND);                                    // 20 s more at speed 10
        assertEquals(START.plusSeconds(80), clock.instant());
        assertEquals(10, clock.speed());
    }

    @Test
    void pausedTimeStandsStillAndResumesWhereItStopped() {
        realNanos.addAndGet(SECOND);
        clock.pause();
        realNanos.addAndGet(100 * SECOND);
        assertTrue(clock.paused());
        assertEquals(START.plusSeconds(60), clock.instant());

        clock.resume();
        realNanos.addAndGet(SECOND);
        assertFalse(clock.paused());
        assertEquals(START.plusSeconds(120), clock.instant());
    }

    @Test
    void pausingOrResumingTwiceChangesNothing() {
        clock.pause();
        clock.pause();
        realNanos.addAndGet(SECOND);
        clock.resume();
        clock.resume();
        realNanos.addAndGet(SECOND);
        assertEquals(START.plusSeconds(60), clock.instant());
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScaledClock(null, 60, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> new ScaledClock(START, 60, null));
        assertThrows(IllegalArgumentException.class, () -> new ScaledClock(START, 0, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(-1));
    }
}

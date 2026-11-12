package com.medidispatch.sim.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class TickPacerTest {

    private static final Duration TICK = Duration.ofSeconds(10);
    private static final long ONE_SECOND = 1_000_000_000L;

    @Test
    void atSixtyTimesOneRealSecondPlaysOneSimulatedMinute() {
        TickPacer pacer = new TickPacer(TICK, 60);

        assertEquals(6, pacer.ticksFor(ONE_SECOND));                // 60 simulated seconds = 6 steps of 10 s
    }

    @Test
    void timeIsCarriedOverBetweenFramesSoNothingIsLostToRounding() {
        TickPacer pacer = new TickPacer(TICK, 60);
        int total = 0;

        for (int frame = 0; frame < 60; frame++) {
            total += pacer.ticksFor(16_666_667);                    // sixty frames of about 1/60 s
        }

        assertEquals(6, total);                                     // most frames run 0 steps, some run 1
    }

    @Test
    void theSpeedCanBeChanged() {
        TickPacer pacer = new TickPacer(TICK, 60);

        pacer.setSpeed(300);

        assertEquals(300, pacer.speed());
        assertEquals(30, pacer.ticksFor(ONE_SECOND));
    }

    @Test
    void aLongFreezeIsCappedAndTheRestIsDropped() {
        TickPacer pacer = new TickPacer(TICK, 300);

        assertEquals(TickPacer.MAX_TICKS_PER_FRAME, pacer.ticksFor(10 * ONE_SECOND));   // would be 300 steps
        assertEquals(0, pacer.ticksFor(0));                          // nothing saved up for later
    }

    @Test
    void resetForgetsTheTimeCarriedOver() {
        TickPacer pacer = new TickPacer(TICK, 60);
        assertEquals(0, pacer.ticksFor(ONE_SECOND / 10));          // 6 simulated seconds: not a step yet

        pacer.reset();

        assertEquals(0, pacer.ticksFor(ONE_SECOND / 10));          // without the reset this would be 1
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TickPacer(null, 60));
        assertThrows(IllegalArgumentException.class, () -> new TickPacer(Duration.ZERO, 60));
        assertThrows(IllegalArgumentException.class, () -> new TickPacer(TICK, 0));
        TickPacer pacer = new TickPacer(TICK, 60);
        assertThrows(IllegalArgumentException.class, () -> pacer.setSpeed(-10));
        assertThrows(IllegalArgumentException.class, () -> pacer.ticksFor(-1));
    }
}

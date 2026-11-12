package com.medidispatch.sim.fx;

import java.time.Duration;

/**
 * Decides how many simulation steps to run in each animation frame, so the simulation moves at a chosen speed.
 * <p>
 * Speed is in simulated seconds per real second: at 60, one real second plays one simulated minute. Frames do not
 * line up with steps (a frame is about 17 ms, a step is 10 simulated seconds), so the time not yet used is carried
 * over to the next frame; nothing is lost to rounding and the speed stays exact.
 * <p>
 * Pure arithmetic with no JavaFX in it, so it is easy to test.
 */
public final class TickPacer {

    /** The most steps run in one frame. After a long freeze the rest is dropped instead of racing to catch up. */
    static final int MAX_TICKS_PER_FRAME = 50;

    private final long tickNanos;
    private int speed;
    private long owedNanos;               // simulated time earned but not yet played, in nanoseconds

    /**
     * Creates a pacer.
     *
     * @param tick  how much simulated time one step covers
     * @param speed simulated seconds per real second; must be positive
     * @throws IllegalArgumentException if the tick is missing or not positive, or the speed is not positive
     */
    public TickPacer(Duration tick, int speed) {
        if (tick == null || tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("tick must be positive");
        }
        this.tickNanos = tick.toNanos();
        setSpeed(speed);
    }

    /**
     * Changes the speed from the next frame on.
     *
     * @param simulatedSecondsPerRealSecond the new speed; must be positive
     * @throws IllegalArgumentException if the speed is not positive
     */
    public void setSpeed(int simulatedSecondsPerRealSecond) {
        if (simulatedSecondsPerRealSecond <= 0) {
            throw new IllegalArgumentException("speed must be positive, got " + simulatedSecondsPerRealSecond);
        }
        this.speed = simulatedSecondsPerRealSecond;
    }

    /**
     * The current speed.
     *
     * @return simulated seconds per real second
     */
    public int speed() {
        return speed;
    }

    /**
     * How many steps to run for the real time that passed since the last frame.
     *
     * @param elapsedNanos real time since the last frame, in nanoseconds
     * @return the number of steps to run now, at most {@link #MAX_TICKS_PER_FRAME}
     * @throws IllegalArgumentException if {@code elapsedNanos} is negative
     */
    public int ticksFor(long elapsedNanos) {
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsed time cannot be negative");
        }
        owedNanos += elapsedNanos * speed;                    // real time times speed = simulated time earned
        long due = owedNanos / tickNanos;
        if (due > MAX_TICKS_PER_FRAME) {
            owedNanos = 0;                                    // too far behind: drop the rest
            return MAX_TICKS_PER_FRAME;
        }
        owedNanos -= due * tickNanos;                         // keep the leftover for the next frame
        return (int) due;
    }

    /** Forgets any time carried over, for example after pausing, so playing again does not start with a burst. */
    public void reset() {
        owedNanos = 0;
    }
}

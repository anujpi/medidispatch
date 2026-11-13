package com.medidispatch.sim;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;

/**
 * A clock that follows real time, sped up: at speed 60, one real second is one simulated minute. It can be paused
 * and its speed changed while it runs, without simulated time ever jumping or going backwards.
 * <p>
 * Thread-safe: every thread in the live simulation reads it, and the window changes it, so every method is
 * {@code synchronized}. Internally it remembers an anchor ("at this real moment, simulated time was this") and
 * counts from there; pausing or changing speed just moves the anchor.
 */
final class ScaledClock extends Clock {

    private final LongSupplier realNanos;      // where real time comes from (System.nanoTime, or a fake in tests)
    private Instant anchorSimulated;
    private long anchorReal;
    private int speed;
    private boolean paused;

    /**
     * Creates a running clock that follows the computer's real time.
     *
     * @param start simulated time right now
     * @param speed simulated seconds per real second; must be positive
     */
    ScaledClock(Instant start, int speed) {
        this(start, speed, () -> System.nanoTime());
    }

    /**
     * Creates a running clock with a given source of real time, for tests.
     *
     * @param start     simulated time right now
     * @param speed     simulated seconds per real second; must be positive
     * @param realNanos returns the current real time in nanoseconds
     * @throws IllegalArgumentException if an argument is missing or the speed is not positive
     */
    ScaledClock(Instant start, int speed, LongSupplier realNanos) {
        if (start == null || realNanos == null) {
            throw new IllegalArgumentException("start and realNanos are required");
        }
        checkSpeed(speed);
        this.realNanos = realNanos;
        this.anchorSimulated = start;
        this.anchorReal = realNanos.getAsLong();
        this.speed = speed;
    }

    /**
     * The current simulated time.
     *
     * @return the anchor plus the real time since then times the speed; frozen while paused
     */
    @Override
    public synchronized Instant instant() {
        if (paused) {
            return anchorSimulated;
        }
        long realElapsed = realNanos.getAsLong() - anchorReal;
        return anchorSimulated.plusNanos(realElapsed * speed);
    }

    /**
     * Changes the speed from now on; simulated time so far is kept.
     *
     * @param newSpeed simulated seconds per real second; must be positive
     * @throws IllegalArgumentException if the speed is not positive
     */
    synchronized void setSpeed(int newSpeed) {
        checkSpeed(newSpeed);
        moveAnchorToNow();
        speed = newSpeed;
    }

    /** Freezes simulated time. Does nothing if already paused. */
    synchronized void pause() {
        if (!paused) {
            moveAnchorToNow();
            paused = true;
        }
    }

    /** Lets simulated time run again from where it was frozen. Does nothing if not paused. */
    synchronized void resume() {
        if (paused) {
            anchorReal = realNanos.getAsLong();
            paused = false;
        }
    }

    synchronized boolean paused() { return paused; }
    synchronized int speed()      { return speed; }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    // "from this real moment on, count from the current simulated time"
    private void moveAnchorToNow() {
        anchorSimulated = instant();
        anchorReal = realNanos.getAsLong();
    }

    private static void checkSpeed(int speed) {
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be positive, got " + speed);
        }
    }
}

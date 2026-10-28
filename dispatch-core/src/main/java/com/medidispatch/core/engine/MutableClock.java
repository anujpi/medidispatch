package com.medidispatch.core.engine;

import java.time.*;

/**
 * Test helper: a {@link Clock} that stands still until a test moves it with {@link #advance(Duration)}.
 * <p>
 * Lets time-based tests (timeouts, stalled units) run instantly and exactly. Always in UTC.
 * Only tests use it, so it belongs in {@code src/test}.
 */
public class MutableClock extends Clock {

    private Instant now ;

    /**
     * Creates a clock frozen at {@code start}.
     *
     * @param start the starting time
     */
    public MutableClock(Instant start){
        this.now  = start;
    }
    /**
     * Moves the clock forward.
     *
     * @param amount how far to move it
     */
    public void advance(Duration amount){
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

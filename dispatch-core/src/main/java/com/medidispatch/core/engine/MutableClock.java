package com.medidispatch.core.engine;

import java.time.*;

public class MutableClock extends Clock {

    private Instant now ;
    public MutableClock(Instant start){
        this.now  = start;
    }
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

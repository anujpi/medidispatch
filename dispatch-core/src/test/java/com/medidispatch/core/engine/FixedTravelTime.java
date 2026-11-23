package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.TravelTime;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A test {@link TravelTime} with made-up road times: the time from a given starting point (a unit's or ambulance's
 * position) is whatever the test says, so a test can make a farther unit faster "by road". Starting points it was not
 * told about fall back to the straight line. {@link #unreachable} marks a start that cannot reach anything.
 */
final class FixedTravelTime implements TravelTime {

    private final Map<Location, Double> secondsFrom = new HashMap<>();
    private final Map<Location, Double> secondsTo = new HashMap<>();
    private final StraightLineTravelTime fallback = new StraightLineTravelTime();

    /** Trips starting at {@code from} take {@code seconds}, wherever they go. */
    FixedTravelTime from(Location from, double seconds) {
        secondsFrom.put(from, seconds);
        return this;
    }

    /** Trips ending at {@code to} take {@code seconds}, wherever they start (unless a start time is set). */
    FixedTravelTime to(Location to, double seconds) {
        secondsTo.put(to, seconds);
        return this;
    }

    /** Trips starting at {@code from} cannot reach anywhere. */
    FixedTravelTime unreachable(Location from) {
        secondsFrom.put(from, Double.NaN);
        return this;
    }

    /** Trips ending at {@code to} cannot be made. */
    FixedTravelTime unreachableTo(Location to) {
        secondsTo.put(to, Double.NaN);
        return this;
    }

    @Override
    public OptionalDouble seconds(Location from, Location to, boolean emergency) {
        Double s = secondsFrom.containsKey(from) ? secondsFrom.get(from) : secondsTo.get(to);
        if (s == null) {
            return fallback.seconds(from, to, emergency);
        }
        return s.isNaN() ? OptionalDouble.empty() : OptionalDouble.of(s);
    }
}

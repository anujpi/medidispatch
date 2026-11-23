package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.TravelTime;

import java.util.OptionalDouble;

/**
 * The default {@link TravelTime}: a straight line at {@link #SPEED_KMH}, with or without a siren (a straight line has
 * no traffic to make way). Time is distance times a constant, so ordering by it is exactly ordering by distance: an
 * engine using this default behaves as it did before travel times existed.
 */
public final class StraightLineTravelTime implements TravelTime {

    /** The assumed average speed, in km/h. */
    public static final double SPEED_KMH = 40;

    /**
     * @return the straight-line distance at {@link #SPEED_KMH}, in seconds; never empty
     * @throws IllegalArgumentException if a point is missing
     */
    @Override
    public OptionalDouble seconds(Location from, Location to, boolean emergency) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        return OptionalDouble.of(from.distanceKmTo(to) / SPEED_KMH * 3600);
    }
}

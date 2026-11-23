package com.medidispatch.core.port;

import com.medidispatch.core.domain.Location;

import java.util.OptionalDouble;

/**
 * Port: how long it takes to get from one place to another. The engine asks this wherever "nearest" really means
 * "soonest there": which free unit to send, which hospital to take a patient to, whether a first responder beats the
 * ambulance.
 * <p>
 * The core's default ({@link com.medidispatch.core.engine.StraightLineTravelTime}) is a straight line at a fixed
 * speed, so ordering by it is ordering by distance. The road-network version (A* on real roads, with traffic) lives in
 * the routing module and is plugged in by the service.
 */
public interface TravelTime {

    /**
     * @param from      where the trip starts
     * @param to        where it ends
     * @param emergency {@code true} for a vehicle with its siren on (traffic makes way)
     * @return the travel time in seconds, or empty if {@code to} cannot be reached from {@code from} (for example a
     *         closure cuts it off)
     */
    OptionalDouble seconds(Location from, Location to, boolean emergency);
}

package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;

import java.util.Random;

/**
 * The simulated city: a 20 km by 20 km square around a centre point, and straight-line movement inside it.
 * <p>
 * Straight lines between latitude/longitude points are fine at city scale; there are no roads.
 */
final class CityMap {

    /** Centre of the city (Bangalore). */
    static final Location CENTER = new Location(12.9716, 77.5946);

    /** Width and height of the city in kilometres. */
    static final double SIZE_KM = 20.0;

    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    private CityMap() {}

    /**
     * A random point inside the city.
     *
     * @param random where the randomness comes from
     * @return a point at most {@link #SIZE_KM}/2 from the centre in each direction
     */
    static Location randomPoint(Random random) {
        double halfLat = SIZE_KM / 2 / KM_PER_DEGREE_LATITUDE;
        // a degree of longitude gets shorter away from the equator
        double halfLon = SIZE_KM / 2 / (KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(CENTER.latitude())));
        double lat = CENTER.latitude() + (random.nextDouble() * 2 - 1) * halfLat;
        double lon = CENTER.longitude() + (random.nextDouble() * 2 - 1) * halfLon;
        return new Location(lat, lon);
    }

    /**
     * Moves from one point towards another by a given distance, never past the destination.
     *
     * @param from start point
     * @param to   destination
     * @param km   how far to move
     * @return the new point, or exactly {@code to} if it is within {@code km}
     */
    static Location towards(Location from, Location to, double km) {
        double total = from.distanceKmTo(to);
        if (total <= km) {
            return to;
        }
        double share = km / total;
        return new Location(from.latitude() + (to.latitude() - from.latitude()) * share,
                            from.longitude() + (to.longitude() - from.longitude()) * share);
    }
}

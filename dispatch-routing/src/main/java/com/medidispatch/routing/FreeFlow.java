package com.medidispatch.routing;

/**
 * Driving with nobody in the way: every road at its class's free-flow speed ({@link RoadClass#speedKmh()}). A 450 m
 * piece of primary road at 45 km/h (12.5 m/s) costs 36 s.
 */
public final class FreeFlow implements CostModel {

    // worked out from the enum, not hard-coded, so adding a faster road type cannot quietly break A*
    private static final double MAX_METRES_PER_SECOND;

    static {
        int fastest = 0;
        for (RoadClass c : RoadClass.values()) {
            fastest = Math.max(fastest, c.speedKmh());
        }
        MAX_METRES_PER_SECOND = fastest / 3.6;
    }

    /** @return length / speed (km/h divided by 3.6 is metres per second) */
    @Override
    public double seconds(RoadGraph g, int edge) {
        return g.length(edge) / (g.roadClass(edge).speedKmh() / 3.6);
    }

    /** @return the fastest road class's speed, in metres per second */
    @Override
    public double maxSpeedMetresPerSecond() {
        return MAX_METRES_PER_SECOND;
    }
}

package com.medidispatch.routing;

/**
 * What driving a road piece costs, in seconds. The router minimises the total, so this decides what "fastest" means:
 * {@link FreeFlow} uses each road class's speed; the traffic model (R3) slows roads down by time of day.
 * <p>
 * A* also needs {@link #maxSpeedMetresPerSecond()}: its estimate of the time still needed is the straight-line
 * distance at that speed, which must never be more than the real time. So it has to be at least as fast as ANY road
 * can be driven under this model.
 */
public interface CostModel {

    /**
     * @param g    the graph
     * @param from the node the road piece starts at (a CSR edge does not know it; incidents need the road's midpoint)
     * @param edge a road piece leaving {@code from}
     * @return how many seconds driving it takes: never negative; {@code Double.POSITIVE_INFINITY} for a road that
     *         cannot be driven (closed), which the router skips
     */
    double seconds(RoadGraph g, int from, int edge);

    /**
     * @return the fastest any road can be driven under this model, in metres per second
     */
    double maxSpeedMetresPerSecond();
}

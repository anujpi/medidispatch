package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * A route found by the {@link Router}.
 * <p>
 * A record holding an array compares arrays by identity in {@code equals}; nothing compares routes that way (tests
 * compare the seconds and the node lists).
 *
 * @param nodes    the nodes from the start to the target, both included
 * @param seconds  the total travel time under the cost model used
 * @param metres   the total length of the roads driven
 * @param explored how many nodes the search took off its queue: the honest measure of how much work it did
 */
public record Route(int[] nodes, double seconds, double metres, int explored) {

    /**
     * @throws IllegalArgumentException if there are no nodes or a number is negative
     */
    public Route {
        if (nodes == null || nodes.length == 0) {
            throw new IllegalArgumentException("a route has at least one node");
        }
        if (seconds < 0 || metres < 0 || explored < 0) {
            throw new IllegalArgumentException("seconds, metres and explored cannot be negative");
        }
    }

    /** @return the start node */
    public int from() { return nodes[0]; }

    /** @return the target node */
    public int to()   { return nodes[nodes.length - 1]; }

    /**
     * @param g the graph the route was found in
     * @return the route as points, for drawing on a map
     */
    public List<Location> points(RoadGraph g) {
        List<Location> points = new ArrayList<>(nodes.length);
        for (int node : nodes) {
            points.add(g.location(node));
        }
        return points;
    }
}

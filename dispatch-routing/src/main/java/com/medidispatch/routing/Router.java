package com.medidispatch.routing;

import java.util.Arrays;
import java.util.Optional;

/**
 * Finds the fastest route between two nodes of a {@link RoadGraph}, with Dijkstra's algorithm or A*. "Fastest" is
 * whatever the {@link CostModel} says a road costs in seconds.
 * <p>
 * <b>Dijkstra</b> grows outward from the start in rings of equal travel time: it always takes the cheapest unsettled
 * node off a priority queue, settles it (every road takes positive time, so no cheaper way to it can exist), and
 * offers its neighbours a way through it. When the target comes off the queue, its time is final. Correct, but it
 * spreads in every direction.
 * <p>
 * <b>A*</b> is the same search with a sense of direction: a node's priority is the time so far PLUS an estimate of the
 * time still needed, the straight-line distance to the target at the fastest speed any road allows. That estimate
 * never overestimates (nothing beats a straight line at top speed) and is consistent (a step along a road never
 * lowers time-so-far + estimate by more than the road costs: the triangle inequality, since road lengths are measured
 * with the same formula), so A* gives exactly Dijkstra's answer while exploring far fewer nodes.
 * <p>
 * Both share one search ({@code guided} picks the priority: that one line is the whole difference). Dijkstra stays as
 * the reference the tests check A* against.
 */
// ponytail: each search allocates its arrays (about 5 MB for Bangalore, about a millisecond); reuse them with a
// per-search stamp if routing ever runs thousands of times a second. Bidirectional search and contraction
// hierarchies (how very large routers answer in microseconds) are left for later.
public final class Router {

    /** The same Earth radius as Location.distanceKmTo, so road lengths and estimates agree. */
    static final double EARTH_RADIUS_METRES = 6_371_000;

    /**
     * Road lengths are stored as floats (about 7 digits); on a rare road the double estimate could come out a few
     * millimetres longer than the stored length, breaking "never overestimate" by a hair. Shaving 0.01% off the
     * estimate absorbs that at no measurable cost.
     */
    static final double ESTIMATE_MARGIN = 0.9999;

    private final RoadGraph g;
    private final CostModel cost;

    /**
     * @param g    the road network
     * @param cost what a road costs in seconds
     * @throws IllegalArgumentException if either is missing
     */
    public Router(RoadGraph g, CostModel cost) {
        if (g == null || cost == null) {
            throw new IllegalArgumentException("graph and cost model are required");
        }
        this.g = g;
        this.cost = cost;
    }

    /**
     * @param from the start node
     * @param to   the target node
     * @return the fastest route, or empty if the target cannot be reached
     * @throws IllegalArgumentException if a node is not in the graph
     */
    public Optional<Route> dijkstra(int from, int to) {
        return search(from, to, false);
    }

    /**
     * The same answer as {@link #dijkstra}, usually after exploring far fewer nodes.
     *
     * @param from the start node
     * @param to   the target node
     * @return the fastest route, or empty if the target cannot be reached
     * @throws IllegalArgumentException if a node is not in the graph
     */
    public Optional<Route> aStar(int from, int to) {
        return search(from, to, true);
    }

    private Optional<Route> search(int from, int to, boolean guided) {
        checkNode(from);
        checkNode(to);
        int n = g.nodeCount();
        double[] dist = new double[n];                        // best time found so far to each node
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        int[] cameFrom = new int[n];                          // the node it was best reached from...
        int[] cameBy = new int[n];                            // ...and the road (a CSR edge does not know its start)
        boolean[] settled = new boolean[n];

        MinHeap heap = new MinHeap();
        dist[from] = 0;
        cameFrom[from] = -1;
        heap.push(0, from);
        int explored = 0;
        while (!heap.isEmpty()) {
            int node = heap.pop();
            if (settled[node]) {
                continue;                                     // a stale copy: a cheaper one came off earlier
            }
            settled[node] = true;
            explored++;
            if (node == to) {
                return Optional.of(route(from, to, dist[to], cameFrom, cameBy, explored));
            }
            for (int e = g.firstEdge(node); e < g.edgeEnd(node); e++) {
                int next = g.target(e);
                if (settled[next]) {
                    continue;
                }
                double through = dist[node] + cost.seconds(g, e);
                if (through < dist[next]) {                   // a cheaper way to next
                    dist[next] = through;
                    cameFrom[next] = node;
                    cameBy[next] = e;
                    heap.push(guided ? through + estimate(next, to) : through, next);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A*'s estimate: the straight-line time from a node to the target at the model's top speed, shaved by
     * {@link #ESTIMATE_MARGIN}. Never more than the real fastest time.
     *
     * @param node   any node
     * @param target the target node
     * @return the estimate in seconds
     */
    double estimate(int node, int target) {
        return ESTIMATE_MARGIN * metres(g.latitude(node), g.longitude(node), g.latitude(target), g.longitude(target))
                / cost.maxSpeedMetresPerSecond();
    }

    // walks back from the target along cameFrom, adding up the real metres
    private Route route(int from, int to, double seconds, int[] cameFrom, int[] cameBy, int explored) {
        int length = 1;
        for (int node = to; node != from; node = cameFrom[node]) {
            length++;
        }
        int[] nodes = new int[length];
        double metres = 0;
        int i = length - 1;
        for (int node = to; node != from; node = cameFrom[node]) {
            nodes[i--] = node;
            metres += g.length(cameBy[node]);
        }
        nodes[0] = from;
        return new Route(nodes, seconds, metres, explored);
    }

    private void checkNode(int node) {
        if (node < 0 || node >= g.nodeCount()) {
            throw new IllegalArgumentException("no node " + node + " in a graph of " + g.nodeCount());
        }
    }

    /**
     * The haversine formula on raw coordinates, with the same radius as Location (no objects created: it runs for
     * every node A* touches).
     *
     * @return the great-circle distance in metres
     */
    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.sqrt(a));
    }
}

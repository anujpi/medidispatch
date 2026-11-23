package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.TravelTime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The core's {@link TravelTime} question answered with real roads: snap both points to the nearest road node, freeze
 * the traffic at this moment ({@link TrafficModel#at}), run A* between the two nodes, and add the "snap legs" (house to
 * road and road to house, in a straight line at 20 km/h: usually tens of metres, but a patient far from any road
 * should not count as being on it). No route (a closure cut it off) gives empty.
 * <p>
 * Thread-safe without locks: the graph and the snapping grid are read-only, every search makes its own arrays, the
 * traffic model's incidents are concurrent, and every call freezes its own traffic picture.
 */
public final class RoadTravelTime implements TravelTime {

    /** Speed for the short straight bits between a point and its road node. */
    static final double SNAP_LEG_METRES_PER_SECOND = 20 / 3.6;

    private final RoadGraph g;
    private final NearestNode snap;
    private final TrafficModel traffic;
    private final Clock clock;

    /**
     * @param g       the road network
     * @param traffic rush hours and incidents
     * @param clock   what "now" is, for the traffic (the engine's clock)
     * @throws IllegalArgumentException if an argument is missing or the graph has no nodes
     */
    public RoadTravelTime(RoadGraph g, TrafficModel traffic, Clock clock) {
        if (g == null || traffic == null || clock == null) {
            throw new IllegalArgumentException("graph, traffic and clock are required");
        }
        this.g = g;
        this.snap = new NearestNode(g);
        this.traffic = traffic;
        this.clock = clock;
    }

    /**
     * @param graphFile a file written by {@link GraphFile} (for example {@code data/bangalore.graph})
     * @param traffic   rush hours and incidents
     * @param clock     what "now" is
     * @return the travel times on that graph
     * @throws IOException if the file cannot be read or is not a road graph file
     */
    public static RoadTravelTime load(Path graphFile, TrafficModel traffic, Clock clock) throws IOException {
        try (InputStream in = Files.newInputStream(graphFile)) {
            return new RoadTravelTime(GraphFile.read(in), traffic, clock);
        }
    }

    /**
     * @return the road route's time in the traffic right now, plus the snap legs; empty if there is no route
     * @throws IllegalArgumentException if a point is missing
     */
    @Override
    public OptionalDouble seconds(Location from, Location to, boolean emergency) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        int a = snap.nearest(from);
        int b = snap.nearest(to);
        double legMetres = (from.distanceKmTo(g.location(a)) + to.distanceKmTo(g.location(b))) * 1000;
        Optional<Route> route = new Router(g, traffic.at(clock.instant(), emergency)).aStar(a, b);
        if (route.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(route.get().seconds() + legMetres / SNAP_LEG_METRES_PER_SECOND);
    }

    /** @return the road network (for startup logging, and for drawing routes) */
    public RoadGraph graph() {
        return g;
    }
}

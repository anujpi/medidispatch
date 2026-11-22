package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Dijkstra against A* on the real Bangalore graph: for two trips (a short one across south Bangalore and a long one
 * right across the city), the travel time, the distance, how many nodes each algorithm explored, and the best of five
 * timed runs (the first, untimed run warms the JVM up). Then the short trip with traffic: at night, at rush hour, as an
 * ambulance, and with a road closed halfway along the rush-hour route.
 * <p>
 * Argument (optional): the graph file, default {@code data/bangalore.graph}. Run from the project's root folder.
 */
public final class RouteDemo {

    private RouteDemo() {}

    /**
     * @param args optional: the graph file
     * @throws Exception if the graph cannot be read
     */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "data/bangalore.graph");
        long started = System.currentTimeMillis();
        RoadGraph g;
        try (InputStream in = Files.newInputStream(file)) {
            g = GraphFile.read(in);
        }
        System.out.printf("loaded %,d nodes, %,d edges in %d ms%n", g.nodeCount(), g.edgeCount(),
                System.currentTimeMillis() - started);
        NearestNode snap = new NearestNode(g);
        Router router = new Router(g, new FreeFlow());
        compare("Koramangala -> Jayanagar", snap, router,
                new Location(12.9352, 77.6245), new Location(12.9250, 77.5938));
        compare("Electronic City -> Hebbal", snap, router,
                new Location(12.8452, 77.6602), new Location(13.0358, 77.5970));

        // the same short trip with traffic: night, rush hour, an ambulance, then a closure on the rush-hour route
        TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());
        int from = snap.nearest(new Location(12.9352, 77.6245));
        int to = snap.nearest(new Location(12.9250, 77.5938));
        System.out.printf("%nKoramangala -> Jayanagar with traffic:%n");
        Route night = trafficRoute("03:00, normal   ", g, traffic, LocalTime.of(3, 0), false, from, to);
        Route rush = trafficRoute("09:30, normal   ", g, traffic, LocalTime.of(9, 30), false, from, to);
        trafficRoute("09:30, emergency", g, traffic, LocalTime.of(9, 30), true, from, to);
        System.out.printf("  the rush-hour route differs from the night route: %s%n",
                !Arrays.equals(night.nodes(), rush.nodes()));
        Location blocked = g.location(rush.nodes()[rush.nodes().length / 2]);
        traffic.add(Incident.closure("demo-closure", blocked, 150));
        trafficRoute("09:30, emergency, road closed halfway", g, traffic, LocalTime.of(9, 30), true, from, to);
    }

    private static Route trafficRoute(String name, RoadGraph g, TrafficModel traffic, LocalTime time,
                                      boolean emergency, int from, int to) {
        Route route = new Router(g, traffic.at(time, emergency)).aStar(from, to).orElseThrow();
        System.out.printf("  %s %.1f min, %.1f km, %,d nodes explored%n", name, route.seconds() / 60,
                route.metres() / 1000, route.explored());
        return route;
    }

    private static void compare(String name, NearestNode snap, Router router, Location a, Location b) {
        int from = snap.nearest(a);
        int to = snap.nearest(b);
        System.out.printf("%n%s: %.1f km in a straight line%n", name, a.distanceKmTo(b));
        report("Dijkstra", () -> router.dijkstra(from, to).orElseThrow());
        report("A*      ", () -> router.aStar(from, to).orElseThrow());
    }

    private static void report(String name, Supplier<Route> search) {
        Route route = search.get();
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            search.get();
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf("  %s %.1f min, %.1f km, %,d nodes explored, %.1f ms%n", name, route.seconds() / 60,
                route.metres() / 1000, route.explored(), best / 1e6);
    }
}

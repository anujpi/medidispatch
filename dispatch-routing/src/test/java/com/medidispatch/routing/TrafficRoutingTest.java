package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The router with traffic: the fastest route depends on the time and on what is happening on the roads. */
public class TrafficRoutingTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;
    private static final double KM = 0.009;

    /**
     * A to B: a residential lane straight there (2.2 km) or primary roads via C (about 3 km). At night the primary roads
     * win (45 against 20 km/h); at the evening peak they crawl at 0.4 (18 km/h) while the lane keeps 0.82 (16.4 km/h),
     * and the shorter lane wins.
     */
    private static RoadGraph laneOrMainRoad() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);                               // A
        b.addNode(1, LAT + 2.2 * KM, LON);                    // B
        b.addNode(2, LAT + 1.1 * KM, LON + 0.0095);           // C, off to the east
        b.addRoad(0, 1, RoadClass.RESIDENTIAL, true, true);
        b.addRoad(0, 2, RoadClass.PRIMARY, true, true);
        b.addRoad(2, 1, RoadClass.PRIMARY, true, true);
        return b.build();
    }

    @Test
    void atNightTheMainRoadWinsAtRushHourTheLaneDoes() {
        RoadGraph g = laneOrMainRoad();
        TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());

        Route night = new Router(g, traffic.at(LocalTime.of(3, 0), false)).aStar(0, 1).orElseThrow();
        Route peak = new Router(g, traffic.at(LocalTime.of(18, 0), false)).aStar(0, 1).orElseThrow();

        assertArrayEquals(new int[] {0, 2, 1}, night.nodes());
        assertArrayEquals(new int[] {0, 1}, peak.nodes());
    }

    /**
     * Closing the main road A-C sends the route down the lane, even at night. The circle is centred on the road's
     * middle: a road piece counts as inside when its MIDPOINT is (real OpenStreetMap pieces are a few metres long; this
     * one is 1.5 km, so a circle around junction C alone would miss it).
     */
    @Test
    void aClosureForcesADetour() {
        RoadGraph g = laneOrMainRoad();
        TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());
        Location middleOfAC = new Location((g.latitude(0) + g.latitude(2)) / 2, (g.longitude(0) + g.longitude(2)) / 2);

        traffic.add(Incident.closure("flyover", middleOfAC, 200));
        Route route = new Router(g, traffic.at(LocalTime.of(3, 0), false)).aStar(0, 1).orElseThrow();

        assertArrayEquals(new int[] {0, 1}, route.nodes());
    }

    @Test
    void closingTheOnlyRoadLeavesNoRoute() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        b.addNode(1, LAT + KM, LON);
        b.addRoad(0, 1, RoadClass.PRIMARY, true, true);
        RoadGraph g = b.build();
        TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());

        traffic.add(Incident.closure("cut", new Location(LAT + KM / 2, LON), 200));

        assertEquals(Optional.empty(), new Router(g, traffic.at(LocalTime.NOON, true)).aStar(0, 1));
        assertEquals(Optional.empty(), new Router(g, traffic.at(LocalTime.NOON, true)).dijkstra(0, 1));
    }

    /**
     * On random grids, with random jams and closures, at random times, for both kinds of vehicle: A* still finds
     * exactly Dijkstra's cost, and its estimate still never overestimates.
     */
    @Test
    void aStarStillMatchesDijkstraWithTraffic() {
        Random dice = new Random(23);
        for (int graph = 0; graph < 3; graph++) {
            RoadGraph g = RouterPropertyTest.randomGrid(100 + graph);
            TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());
            for (int i = 0; i < 6; i++) {
                Location centre = g.location(dice.nextInt(g.nodeCount()));
                double factor = dice.nextDouble() < 0.3 ? 0 : 0.1 + dice.nextDouble() * 0.8;
                traffic.add(new Incident("i" + i, centre, 50 + dice.nextDouble() * 250, factor));
            }
            for (int pair = 0; pair < 40; pair++) {
                LocalTime time = LocalTime.of(dice.nextInt(24), dice.nextInt(60));
                boolean emergency = dice.nextBoolean();
                Router router = new Router(g, traffic.at(time, emergency));
                int from = dice.nextInt(g.nodeCount());
                int to = dice.nextInt(g.nodeCount());
                Optional<Route> d = router.dijkstra(from, to);
                Optional<Route> a = router.aStar(from, to);

                assertEquals(d.isPresent(), a.isPresent());
                if (d.isPresent()) {
                    assertEquals(d.get().seconds(), a.get().seconds(), 1e-6);
                    assertTrue(router.estimate(from, to) <= d.get().seconds() + 1e-9);
                }
            }
        }
    }
}

package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Small hand-made graphs where the right answer is known. Every check runs both algorithms. */
public class RouterTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;
    private static final double KM = 0.009;                  // degrees of latitude in about 1 km

    private static Router router(RoadGraph g) {
        return new Router(g, new FreeFlow());
    }

    private static void bothAgree(RoadGraph g, int from, int to, double expectedSeconds) {
        Route d = router(g).dijkstra(from, to).orElseThrow();
        Route a = router(g).aStar(from, to).orElseThrow();
        assertEquals(expectedSeconds, d.seconds(), 0.5);
        assertEquals(d.seconds(), a.seconds(), 1e-6);
    }

    /**
     * A to B: a direct residential lane of 2 km (20 km/h: 360 s), or a 3-km detour on a primary road via C
     * (45 km/h: about 240 s). Fastest is the longer road.
     */
    @Test
    void aLongFastRoadBeatsAShortSlowOne() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);                               // A
        b.addNode(1, LAT + 2 * KM, LON);                      // B, 2 km north
        b.addNode(2, LAT + KM, LON + 0.0115);                 // C, off to the east
        b.addRoad(0, 1, RoadClass.RESIDENTIAL, true, true);
        b.addRoad(0, 2, RoadClass.PRIMARY, true, true);
        b.addRoad(2, 1, RoadClass.PRIMARY, true, true);
        RoadGraph g = b.build();

        Route route = router(g).aStar(0, 1).orElseThrow();

        assertArrayEquals(new int[] {0, 2, 1}, route.nodes());
        assertTrue(route.seconds() < 2_000 / (20 / 3.6), "the detour must be faster than the lane");
        bothAgree(g, 0, 1, route.seconds());
    }

    /** A one-way street that points the wrong way is not used: the route goes around. */
    @Test
    void aOneWayStreetIsNotDrivenTheWrongWay() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        b.addNode(1, LAT + KM, LON);
        b.addNode(2, LAT + KM, LON + KM);
        b.addRoad(1, 0, RoadClass.PRIMARY, true, false);      // one way, from 1 to 0 only
        b.addRoad(0, 2, RoadClass.PRIMARY, true, true);
        b.addRoad(2, 1, RoadClass.PRIMARY, true, true);
        RoadGraph g = b.build();

        assertArrayEquals(new int[] {0, 2, 1}, router(g).dijkstra(0, 1).orElseThrow().nodes());
        assertArrayEquals(new int[] {0, 2, 1}, router(g).aStar(0, 1).orElseThrow().nodes());
        assertArrayEquals(new int[] {1, 0}, router(g).aStar(1, 0).orElseThrow().nodes());
    }

    @Test
    void anUnreachableTargetGivesNoRoute() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        b.addNode(1, LAT + KM, LON);
        b.addRoad(1, 0, RoadClass.PRIMARY, true, false);      // you can come in, never go
        RoadGraph g = b.build();

        assertEquals(Optional.empty(), router(g).dijkstra(0, 1));
        assertEquals(Optional.empty(), router(g).aStar(0, 1));
    }

    @Test
    void aRouteToTheStartIsOneNodeAndNoTime() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        RoadGraph g = b.build();

        Route route = router(g).aStar(0, 0).orElseThrow();

        assertArrayEquals(new int[] {0}, route.nodes());
        assertEquals(0, route.seconds());
        assertEquals(0, route.metres());
        assertEquals(0, route.from());
        assertEquals(0, route.to());
    }

    /** Every step is a real road, and the totals are the sums over those roads. */
    @Test
    void thePathIsContinuousAndItsTotalsAddUp() {
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < 6; i++) {
            b.addNode(i, LAT + i * 0.002, LON + (i % 2) * 0.001);
        }
        for (int i = 1; i < 6; i++) {
            b.addRoad(i - 1, i, i % 2 == 0 ? RoadClass.SECONDARY : RoadClass.TERTIARY, true, true);
        }
        RoadGraph g = b.build();
        FreeFlow cost = new FreeFlow();

        Route route = router(g).aStar(0, 5).orElseThrow();

        double seconds = 0;
        double metres = 0;
        for (int i = 1; i < route.nodes().length; i++) {
            int edge = edgeBetween(g, route.nodes()[i - 1], route.nodes()[i]);
            assertTrue(edge >= 0, "no road from " + route.nodes()[i - 1] + " to " + route.nodes()[i]);
            seconds += cost.seconds(g, edge);
            metres += g.length(edge);
        }
        assertEquals(seconds, route.seconds(), 1e-9);
        assertEquals(metres, route.metres(), 1e-6);
        assertEquals(6, route.points(g).size());
        assertEquals(g.location(5), route.points(g).get(5));
    }

    @Test
    void nodesOutsideTheGraphAndMissingArgumentsAreRefused() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        RoadGraph g = b.build();

        assertThrows(IllegalArgumentException.class, () -> router(g).aStar(0, 1));
        assertThrows(IllegalArgumentException.class, () -> router(g).dijkstra(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Router(null, new FreeFlow()));
        assertThrows(IllegalArgumentException.class, () -> new Router(g, null));
    }

    static int edgeBetween(RoadGraph g, int from, int to) {
        for (int e = g.firstEdge(from); e < g.edgeEnd(from); e++) {
            if (g.target(e) == to) {
                return e;
            }
        }
        return -1;
    }
}

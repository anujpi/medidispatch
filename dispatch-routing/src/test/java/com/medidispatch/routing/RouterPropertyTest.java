package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A* checked against Dijkstra on random street grids: 30 x 30 junctions about 110 m apart, random road classes, one
 * street in ten one-way, a few streets missing. Whatever the grid, both must find routes of the same cost, A* must not
 * work harder, and its estimate must never exceed the real time.
 */
public class RouterPropertyTest {

    private static final int SIDE = 30;

    private static RoadGraph randomGrid(long seed) {
        Random dice = new Random(seed);
        RoadClass[] classes = RoadClass.values();
        GraphBuilder b = new GraphBuilder();
        for (int r = 0; r < SIDE; r++) {
            for (int c = 0; c < SIDE; c++) {
                // slightly wobbly junctions, like a real city
                b.addNode(r * SIDE + c, 12.97 + r * 0.001 + dice.nextDouble() * 0.0002,
                        77.59 + c * 0.001 + dice.nextDouble() * 0.0002);
            }
        }
        for (int r = 0; r < SIDE; r++) {
            for (int c = 0; c < SIDE; c++) {
                int here = r * SIDE + c;
                if (c + 1 < SIDE) {
                    street(b, dice, here, here + 1, classes);
                }
                if (r + 1 < SIDE) {
                    street(b, dice, here, here + SIDE, classes);
                }
            }
        }
        return b.build();
    }

    private static void street(GraphBuilder b, Random dice, int a, int c, RoadClass[] classes) {
        double roll = dice.nextDouble();
        if (roll < 0.05) {
            return;                                           // a missing street
        }
        RoadClass cls = classes[dice.nextInt(classes.length)];
        if (roll < 0.10) {
            b.addRoad(a, c, cls, true, false);                // one way
        } else if (roll < 0.15) {
            b.addRoad(a, c, cls, false, true);                // one way, the other direction
        } else {
            b.addRoad(a, c, cls, true, true);
        }
    }

    @Test
    void aStarAlwaysFindsTheSameCostAsDijkstraAndNeverWorksHarder() {
        Random dice = new Random(11);
        for (int graph = 0; graph < 4; graph++) {
            RoadGraph g = randomGrid(graph);
            Router router = new Router(g, new FreeFlow());
            for (int pair = 0; pair < 50; pair++) {
                int from = dice.nextInt(g.nodeCount());
                int to = dice.nextInt(g.nodeCount());
                Optional<Route> d = router.dijkstra(from, to);
                Optional<Route> a = router.aStar(from, to);

                assertEquals(d.isPresent(), a.isPresent(), "reachability " + from + " -> " + to);
                if (d.isPresent()) {
                    assertEquals(d.get().seconds(), a.get().seconds(), 1e-6, "cost " + from + " -> " + to);
                    assertTrue(a.get().explored() <= d.get().explored(),
                            "A* explored " + a.get().explored() + ", Dijkstra " + d.get().explored());
                }
            }
        }
    }

    /** The rule A* rests on: the estimate is never more than the real fastest time to the target. */
    @Test
    void theEstimateNeverOverestimates() {
        RoadGraph g = randomGrid(99);
        Router router = new Router(g, new FreeFlow());
        Random dice = new Random(5);
        int target = dice.nextInt(g.nodeCount());
        for (int i = 0; i < 150; i++) {
            int node = dice.nextInt(g.nodeCount());
            Optional<Route> real = router.dijkstra(node, target);
            if (real.isPresent()) {
                assertTrue(router.estimate(node, target) <= real.get().seconds() + 1e-9,
                        "estimate " + router.estimate(node, target) + " > real " + real.get().seconds());
            }
        }
    }
}

package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphCleanupTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;

    // a two-way square of four junctions, ids 1..4
    private static GraphBuilder square() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, LAT, LON);
        b.addNode(2, LAT + 0.001, LON);
        b.addNode(3, LAT + 0.001, LON + 0.001);
        b.addNode(4, LAT, LON + 0.001);
        b.addRoad(1, 2, RoadClass.RESIDENTIAL, true, true);
        b.addRoad(2, 3, RoadClass.RESIDENTIAL, true, true);
        b.addRoad(3, 4, RoadClass.RESIDENTIAL, true, true);
        b.addRoad(4, 1, RoadClass.RESIDENTIAL, true, true);
        return b;
    }

    @Test
    void aSeparateIslandIsRemoved() {
        GraphBuilder b = square();
        b.addNode(10, LAT + 0.05, LON);                      // a road nobody can reach
        b.addNode(11, LAT + 0.051, LON);
        b.addRoad(10, 11, RoadClass.RESIDENTIAL, true, true);

        RoadGraph clean = GraphCleanup.largestStronglyConnected(b.build());

        assertEquals(4, clean.nodeCount());
        assertEquals(8, clean.edgeCount());
    }

    /**
     * A one-way street leading off the square into a dead end: you can drive in but never out. A plain "connected"
     * check would keep it; a strongly connected one must not.
     */
    @Test
    void aOneWayDeadEndIsRemoved() {
        GraphBuilder b = square();
        b.addNode(5, LAT - 0.001, LON);
        b.addNode(6, LAT - 0.002, LON);
        b.addRoad(1, 5, RoadClass.RESIDENTIAL, true, false);
        b.addRoad(5, 6, RoadClass.RESIDENTIAL, true, true);

        RoadGraph clean = GraphCleanup.largestStronglyConnected(b.build());

        assertEquals(4, clean.nodeCount());
        assertEquals(8, clean.edgeCount());
    }

    /** Everything connected both ways stays, roads and all. */
    @Test
    void aGraphThatIsAlreadyCleanKeepsEverything() {
        RoadGraph clean = GraphCleanup.largestStronglyConnected(square().build());

        assertEquals(4, clean.nodeCount());
        assertEquals(8, clean.edgeCount());
    }

    /**
     * A one-way loop of 200,000 points: a recursive search would overflow Java's call stack (about ten thousand deep);
     * the explicit stack keeps every point.
     */
    @Test
    void aVeryLongLoopDoesNotOverflowTheStack() {
        int points = 200_000;
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < points; i++) {
            b.addNode(i, LAT + (i % 1000) * 0.00001, LON + (i / 1000) * 0.00001);
        }
        for (int i = 0; i < points; i++) {
            b.addRoad(i, (i + 1) % points, RoadClass.RESIDENTIAL, true, false);
        }

        RoadGraph clean = GraphCleanup.largestStronglyConnected(b.build());

        assertEquals(points, clean.nodeCount());
        assertEquals(points, clean.edgeCount());
    }

    @Test
    void anEmptyGraphStaysEmpty() {
        RoadGraph clean = GraphCleanup.largestStronglyConnected(new GraphBuilder().build());

        assertEquals(0, clean.nodeCount());
    }
}

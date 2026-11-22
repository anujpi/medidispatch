package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GraphBuilderTest {

    // 0.009 degrees of latitude is about 1 km
    private static final double LAT = 12.97;
    private static final double LON = 77.59;

    private static List<Integer> targetsOf(RoadGraph g, int node) {
        List<Integer> targets = new ArrayList<>();
        for (int e = g.firstEdge(node); e < g.edgeEnd(node); e++) {
            targets.add(g.target(e));
        }
        return targets;
    }

    /** The example from RoadGraph's description: roads 0->1, 0->2 and 2->1. */
    @Test
    void theEdgesAreGroupedByTheirStartNode() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(100, LAT, LON);
        b.addNode(200, LAT + 0.001, LON);
        b.addNode(300, LAT, LON + 0.001);
        b.addRoad(300, 200, RoadClass.RESIDENTIAL, true, false);   // added first on purpose: order must not matter
        b.addRoad(100, 200, RoadClass.RESIDENTIAL, true, false);
        b.addRoad(100, 300, RoadClass.RESIDENTIAL, true, false);

        RoadGraph g = b.build();

        assertEquals(3, g.nodeCount());
        assertEquals(3, g.edgeCount());
        assertEquals(List.of(1, 2), targetsOf(g, 0));
        assertEquals(List.of(), targetsOf(g, 1));
        assertEquals(List.of(1), targetsOf(g, 2));
        assertEquals(0, g.firstEdge(0));
        assertEquals(2, g.edgeEnd(0));
        assertEquals(2, g.firstEdge(1));
        assertEquals(2, g.edgeEnd(1));
    }

    @Test
    void aTwoWayRoadIsTwoEdgesAndAOneWayStreetOne() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, LAT, LON);
        b.addNode(2, LAT + 0.001, LON);
        b.addNode(3, LAT + 0.002, LON);
        b.addRoad(1, 2, RoadClass.PRIMARY, true, true);
        b.addRoad(2, 3, RoadClass.PRIMARY, false, true);              // one way, from 3 to 2 only

        RoadGraph g = b.build();

        assertEquals(3, g.edgeCount());
        assertEquals(List.of(1), targetsOf(g, 0));
        assertEquals(List.of(0), targetsOf(g, 1));
        assertEquals(List.of(1), targetsOf(g, 2));
    }

    /** About 111 m north, stored in metres, with its class. */
    @Test
    void edgesKnowTheirLengthInMetresAndTheirClass() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, LAT, LON);
        b.addNode(2, LAT + 0.001, LON);
        b.addRoad(1, 2, RoadClass.SECONDARY, true, false);

        RoadGraph g = b.build();

        double expected = new Location(LAT, LON).distanceKmTo(new Location(LAT + 0.001, LON)) * 1000;
        assertEquals(expected, g.length(0), 0.01);
        assertEquals(111, g.length(0), 1);
        assertEquals(RoadClass.SECONDARY, g.roadClass(0));
        assertEquals(new Location(LAT + 0.001, LON), g.location(1));
    }

    /** A junction is in every road through it: adding it again keeps its first number and position. */
    @Test
    void theSameOsmNodeTwiceIsOneNode() {
        GraphBuilder b = new GraphBuilder();

        int first = b.addNode(42, LAT, LON);
        int again = b.addNode(42, 0, 0);

        assertEquals(first, again);
        assertEquals(1, b.nodeCount());
        assertEquals(LAT, b.build().latitude(0));
    }

    @Test
    void aRoadToAnUnknownNodeOrWithoutAClassIsRefused() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, LAT, LON);

        assertThrows(IllegalArgumentException.class, () -> b.addRoad(1, 99, RoadClass.PRIMARY, true, true));
        assertThrows(IllegalArgumentException.class, () -> b.addRoad(99, 1, RoadClass.PRIMARY, true, true));
        b.addNode(2, LAT + 0.001, LON);
        assertThrows(IllegalArgumentException.class, () -> b.addRoad(1, 2, null, true, true));
    }

    @Test
    void aRoadFromANodeToItselfIsSkipped() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(1, LAT, LON);

        b.addRoad(1, 1, RoadClass.PRIMARY, true, true);

        assertEquals(0, b.edgeCount());
    }

    @Test
    void anEmptyBuilderGivesAnEmptyGraph() {
        RoadGraph g = new GraphBuilder().build();

        assertEquals(0, g.nodeCount());
        assertEquals(0, g.edgeCount());
    }

    /** Past the starting size of 1,024 the arrays double, and nothing is lost: a long two-way street of 3,000 points. */
    @Test
    void theArraysGrowPastTheirStartingSize() {
        GraphBuilder b = new GraphBuilder();
        int points = 3_000;
        for (int i = 0; i < points; i++) {
            b.addNode(i, LAT + i * 0.0001, LON);
        }
        for (int i = 1; i < points; i++) {
            b.addRoad(i - 1, i, RoadClass.RESIDENTIAL, true, true);
        }

        RoadGraph g = b.build();

        assertEquals(points, g.nodeCount());
        assertEquals(2 * (points - 1), g.edgeCount());
        assertEquals(List.of(1), targetsOf(g, 0));
        assertEquals(List.of(1498, 1500), targetsOf(g, 1499).stream().sorted().toList());
        assertEquals(LAT + 2999 * 0.0001, g.latitude(2999), 1e-9);
    }
}

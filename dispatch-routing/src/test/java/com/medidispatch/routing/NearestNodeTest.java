package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class NearestNodeTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;

    private static RoadGraph nodesAt(double[][] points) {
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < points.length; i++) {
            b.addNode(i, points[i][0], points[i][1]);
        }
        return b.build();
    }

    // the honest answer: check every node
    private static int bruteForce(RoadGraph g, Location where) {
        int best = -1;
        double bestKm = Double.MAX_VALUE;
        for (int n = 0; n < g.nodeCount(); n++) {
            double km = where.distanceKmTo(g.location(n));
            if (km < bestKm) {
                best = n;
                bestKm = km;
            }
        }
        return best;
    }

    @Test
    void theClosestNodeIsFound() {
        RoadGraph g = nodesAt(new double[][] {{LAT, LON}, {LAT + 0.01, LON}, {LAT, LON + 0.01}});

        NearestNode snap = new NearestNode(g);

        assertEquals(0, snap.nearest(new Location(LAT + 0.001, LON + 0.001)));
        assertEquals(1, snap.nearest(new Location(LAT + 0.009, LON)));
        assertEquals(2, snap.nearest(new Location(LAT, LON + 0.011)));
    }

    /**
     * The point sits at the edge of its cell. Its own cell holds a node at the far side; the next cell holds one just
     * across the edge, which is closer. Stopping at the first node found would give the wrong answer.
     */
    @Test
    void aCloserNodeInTheNextRingWins() {
        double cell = NearestNode.CELL_DEGREES;
        RoadGraph g = nodesAt(new double[][] {
                {LAT, LON},                                   // sets the grid's corner
                {LAT + cell * 4, LON + cell * 4},             // sets the far corner
                {LAT + cell * 2 + cell * 0.05, LON + cell * 2 + cell * 0.5},   // far side of the point's own cell
                {LAT + cell * 3 + cell * 0.02, LON + cell * 2 + cell * 0.5}    // just across the edge, next cell
        });
        Location where = new Location(LAT + cell * 2 + cell * 0.97, LON + cell * 2 + cell * 0.5);

        assertEquals(3, new NearestNode(g).nearest(where));
        assertEquals(bruteForce(g, where), new NearestNode(g).nearest(where));
    }

    /** A patient beyond the city's edge snaps to the closest road point anyway. */
    @Test
    void aPointOutsideTheGraphsBoxStillFindsTheClosestNode() {
        RoadGraph g = nodesAt(new double[][] {{LAT, LON}, {LAT + 0.01, LON + 0.01}});

        NearestNode snap = new NearestNode(g);

        assertEquals(0, snap.nearest(new Location(LAT - 0.5, LON - 0.5)));
        assertEquals(1, snap.nearest(new Location(LAT + 0.5, LON + 0.5)));
    }

    @Test
    void aGraphWithOneNodeAlwaysAnswersThatNode() {
        assertEquals(0, new NearestNode(nodesAt(new double[][] {{LAT, LON}})).nearest(new Location(0, 0)));
    }

    @Test
    void aGraphWithoutNodesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new NearestNode(new GraphBuilder().build()));
    }

    /** The grid must agree with checking every node, for many random points in and around a random city. */
    @Test
    void itAgreesWithBruteForce() {
        Random dice = new Random(42);
        double[][] points = new double[2_000][];
        for (int i = 0; i < points.length; i++) {
            points[i] = new double[] {LAT + dice.nextDouble() * 0.1, LON + dice.nextDouble() * 0.1};
        }
        RoadGraph g = nodesAt(points);
        NearestNode snap = new NearestNode(g);

        for (int i = 0; i < 1_000; i++) {
            Location where = new Location(LAT - 0.02 + dice.nextDouble() * 0.14, LON - 0.02 + dice.nextDouble() * 0.14);
            assertEquals(bruteForce(g, where), snap.nearest(where), "at " + where);
        }
    }
}

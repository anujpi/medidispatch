package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tiny Overpass answers written by hand: no download needed. */
public class OverpassImportTest {

    // nodes 1..4 in a row going north, about 111 m apart
    private static final String NODES = """
            {"type": "node", "id": 1, "lat": 12.970, "lon": 77.590},
            {"type": "node", "id": 2, "lat": 12.971, "lon": 77.590},
            {"type": "node", "id": 3, "lat": 12.972, "lon": 77.590},
            {"type": "node", "id": 4, "lat": 12.973, "lon": 77.590}
            """;

    private static RoadGraph read(String... elements) {
        String json = "{\"version\": 0.6, \"elements\": [" + String.join(",", elements) + "]}";
        return OverpassImport.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String way(String nodes, String tags) {
        return "{\"type\": \"way\", \"id\": 9, \"nodes\": [" + nodes + "], \"tags\": {" + tags + "}}";
    }

    private static List<Integer> targetsOf(RoadGraph g, int node) {
        List<Integer> targets = new ArrayList<>();
        for (int e = g.firstEdge(node); e < g.edgeEnd(node); e++) {
            targets.add(g.target(e));
        }
        return targets;
    }

    /** A way of 4 points is 3 road pieces, in both directions. */
    @Test
    void aTwoWayStreetOfFourPointsIsThreePiecesBothWays() {
        RoadGraph g = read(NODES, way("1, 2, 3, 4", "\"highway\": \"residential\""));

        assertEquals(4, g.nodeCount());
        assertEquals(6, g.edgeCount());
        assertEquals(RoadClass.RESIDENTIAL, g.roadClass(0));
        assertEquals(111, g.length(0), 1);
    }

    @Test
    void aOneWayStreetGoesForwardOnly() {
        RoadGraph g = read(NODES, way("1, 2", "\"highway\": \"primary\", \"oneway\": \"yes\""));

        assertEquals(1, g.edgeCount());
        assertEquals(List.of(1), targetsOf(g, 0));
        assertEquals(List.of(), targetsOf(g, 1));
    }

    @Test
    void onewayMinusOneGoesBackwardOnly() {
        RoadGraph g = read(NODES, way("1, 2", "\"highway\": \"primary\", \"oneway\": \"-1\""));

        assertEquals(List.of(), targetsOf(g, 0));
        assertEquals(List.of(0), targetsOf(g, 1));
    }

    /** Roundabouts and motorways are one-way without saying so; an explicit "no" wins. */
    @Test
    void roundaboutsAndMotorwaysAreOneWayUnlessTaggedOtherwise() {
        assertEquals(1, read(NODES, way("1, 2", "\"highway\": \"tertiary\", \"junction\": \"roundabout\"")).edgeCount());
        assertEquals(1, read(NODES, way("1, 2", "\"highway\": \"motorway\"")).edgeCount());
        assertEquals(1, read(NODES, way("1, 2", "\"highway\": \"motorway_link\"")).edgeCount());
        assertEquals(2, read(NODES,
                way("1, 2", "\"highway\": \"tertiary\", \"junction\": \"roundabout\", \"oneway\": \"no\"")).edgeCount());
    }

    @Test
    void waysThatAreNotRoadsAreSkipped() {
        RoadGraph g = read(NODES, way("1, 2", "\"highway\": \"footway\""), way("2, 3", "\"building\": \"yes\""));

        assertEquals(0, g.edgeCount());
    }

    /** Ways turn into roads at the end, so the order of the answer does not matter. */
    @Test
    void nodesListedAfterTheWaysStillWork() {
        RoadGraph g = read(way("1, 2, 3", "\"highway\": \"secondary\""), NODES);

        assertEquals(4, g.edgeCount());
        assertEquals(RoadClass.SECONDARY, g.roadClass(0));
    }

    /** Overpass answers with more than the elements array; the rest is skipped. */
    @Test
    void otherMembersOfTheAnswerAreIgnored() {
        String json = "{\"version\": 0.6, \"osm3s\": {\"copyright\": \"OSM\"}, \"elements\": [" + NODES + ","
                + way("1, 2", "\"highway\": \"trunk\"") + "]}";

        RoadGraph g = OverpassImport.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, g.edgeCount());
    }
}

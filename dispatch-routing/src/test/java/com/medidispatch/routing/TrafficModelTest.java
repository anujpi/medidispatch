package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The road costs the model gives, on a graph of two separate one-road pieces, A (near) and B (2 km north). */
public class TrafficModelTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;
    private static final LocalTime NIGHT = LocalTime.of(3, 0);
    private static final LocalTime PEAK = LocalTime.of(18, 0);

    private final TrafficModel model = new TrafficModel(TrafficProfile.bangaloreWeekday());

    // edge 0: a primary road around (LAT, LON); edge 1: a primary road 2 km north of it
    private static RoadGraph twoRoads() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, LAT, LON);
        b.addNode(1, LAT + 0.001, LON);
        b.addNode(2, LAT + 0.018, LON);
        b.addNode(3, LAT + 0.019, LON);
        b.addRoad(0, 1, RoadClass.PRIMARY, true, false);
        b.addRoad(2, 3, RoadClass.PRIMARY, true, false);
        return b.build();
    }

    private static double cost(CostModel m, RoadGraph g, int from) {
        return m.seconds(g, from, g.firstEdge(from));
    }

    @Test
    void atNightTheModelIsFreeFlow() {
        RoadGraph g = twoRoads();

        assertEquals(new FreeFlow().seconds(g, 0, 0), cost(model.at(NIGHT, false), g, 0), 1e-9);
    }

    @Test
    void rushHourMakesTheSameRoadSlower() {
        RoadGraph g = twoRoads();

        double night = cost(model.at(NIGHT, false), g, 0);
        double peak = cost(model.at(PEAK, false), g, 0);

        assertEquals(night / 0.4, peak, 1e-6);                // a primary road at 0.4 of its speed
    }

    /** A jam slows the road inside its circle, not the one 2 km away. */
    @Test
    void aJamSlowsOnlyRoadsInsideIt() {
        RoadGraph g = twoRoads();
        double nearBefore = cost(model.at(NIGHT, false), g, 0);
        double farBefore = cost(model.at(NIGHT, false), g, 2);

        model.add(new Incident("jam", new Location(LAT + 0.0005, LON), 300, 0.2));

        assertEquals(nearBefore / 0.2, cost(model.at(NIGHT, false), g, 0), 1e-6);
        assertEquals(farBefore, cost(model.at(NIGHT, false), g, 2), 1e-9);
    }

    @Test
    void aClosureMakesARoadImpassableAndRemovingItReopensIt() {
        RoadGraph g = twoRoads();
        double before = cost(model.at(NIGHT, false), g, 0);

        model.add(Incident.closure("closed", new Location(LAT + 0.0005, LON), 100));
        assertEquals(Double.POSITIVE_INFINITY, cost(model.at(NIGHT, false), g, 0));

        model.remove("closed");
        assertEquals(before, cost(model.at(NIGHT, false), g, 0), 1e-9);
    }

    /** Two overlapping jams multiply. */
    @Test
    void overlappingJamsMultiply() {
        RoadGraph g = twoRoads();
        double before = cost(model.at(NIGHT, false), g, 0);

        model.add(new Incident("a", new Location(LAT + 0.0005, LON), 300, 0.5));
        model.add(new Incident("b", new Location(LAT + 0.0006, LON), 300, 0.5));

        assertEquals(before / 0.25, cost(model.at(NIGHT, false), g, 0), 1e-6);
    }

    /**
     * With a siren: 1.3x the traffic, capped at 1.2x free-flow. At night (no traffic) the cap applies; at the evening
     * peak (0.4) the boost does: 0.52 of free-flow.
     */
    @Test
    void anEmergencyIsFasterThanTrafficButNeverAboveTheCap() {
        RoadGraph g = twoRoads();
        double free = new FreeFlow().seconds(g, 0, 0);

        assertEquals(free / 1.2, cost(model.at(NIGHT, true), g, 0), 1e-6);
        assertEquals(free / (0.4 * 1.3), cost(model.at(PEAK, true), g, 0), 1e-6);
        assertTrue(cost(model.at(PEAK, true), g, 0) < cost(model.at(PEAK, false), g, 0));
    }

    /** A* needs the top speed to cover everything the model allows, siren included. */
    @Test
    void theTopSpeedCoversTheSiren() {
        double motorway = RoadClass.MOTORWAY.speedKmh() / 3.6;

        assertEquals(motorway, model.at(PEAK, false).maxSpeedMetresPerSecond(), 1e-9);
        assertEquals(motorway * 1.2, model.at(PEAK, true).maxSpeedMetresPerSecond(), 1e-9);
    }

    /** A frozen picture does not change when an incident is added afterwards. */
    @Test
    void aFrozenPictureKeepsItsIncidents() {
        RoadGraph g = twoRoads();
        CostModel before = model.at(NIGHT, false);

        model.add(Incident.closure("later", new Location(LAT + 0.0005, LON), 100));

        assertNotEquals(Double.POSITIVE_INFINITY, cost(before, g, 0));
    }

    /** 02:30 UTC is 08:00 in Bangalore (UTC+5:30). */
    @Test
    void anInstantIsReadAsBangaloreTime() {
        RoadGraph g = twoRoads();

        assertEquals(cost(model.at(LocalTime.of(8, 0), false), g, 0),
                cost(model.at(Instant.parse("2026-11-23T02:30:00Z"), false), g, 0), 1e-9);
    }

    @Test
    void incidentsAreListedByIdAndBadOnesAreRefused() {
        model.add(new Incident("b", new Location(LAT, LON), 100, 0.5));
        model.add(new Incident("a", new Location(LAT, LON), 100, 0.5));
        model.add(new Incident("a", new Location(LAT, LON), 200, 0.3));   // the same id replaces

        List<Incident> all = model.incidents();
        assertEquals(List.of("a", "b"), List.of(all.get(0).id(), all.get(1).id()));
        assertEquals(200, all.get(0).radiusMetres());

        assertThrows(IllegalArgumentException.class, () -> new Incident(" ", new Location(LAT, LON), 100, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Incident("x", null, 100, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Incident("x", new Location(LAT, LON), 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Incident("x", new Location(LAT, LON), 100, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new Incident("x", new Location(LAT, LON), 100, -0.1));
        assertTrue(Incident.closure("c", new Location(LAT, LON), 50).closed());
    }
}

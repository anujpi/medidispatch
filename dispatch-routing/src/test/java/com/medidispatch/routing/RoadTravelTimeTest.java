package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The core's travel-time question answered on a small road graph: an L-shaped primary road A - B - C, so the road
 * trip from A to C is longer than the straight line. Clocks are fixed: 21:30 UTC is 03:00 in Bangalore (night),
 * 12:30 UTC is 18:00 (the evening peak).
 */
public class RoadTravelTimeTest {

    private static final double LAT = 12.97;
    private static final double LON = 77.59;
    private static final Clock NIGHT = Clock.fixed(Instant.parse("2026-11-23T21:30:00Z"), ZoneOffset.UTC);
    private static final Clock PEAK = Clock.fixed(Instant.parse("2026-11-23T12:30:00Z"), ZoneOffset.UTC);

    private static final Location A = new Location(LAT, LON);
    private static final Location B = new Location(LAT + 0.01, LON);           // about 1.1 km north of A
    private static final Location C = new Location(LAT + 0.01, LON + 0.01);    // about 1.1 km east of B

    private static RoadGraph lShape() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, A.latitude(), A.longitude());
        b.addNode(1, B.latitude(), B.longitude());
        b.addNode(2, C.latitude(), C.longitude());
        b.addRoad(0, 1, RoadClass.PRIMARY, true, true);
        b.addRoad(1, 2, RoadClass.PRIMARY, true, true);
        return b.build();
    }

    private static TrafficModel noIncidents() {
        return new TrafficModel(TrafficProfile.bangaloreWeekday());
    }

    private static double primarySeconds(double km) {
        return km * 1000 / (RoadClass.PRIMARY.speedKmh() / 3.6);
    }

    /** Exactly on the road: the time is the route's, along both legs of the L (longer than the straight line). */
    @Test
    void theTimeIsTheRouteAlongTheRoads() {
        RoadTravelTime travel = new RoadTravelTime(lShape(), noIncidents(), NIGHT);

        double seconds = travel.seconds(A, C, false).getAsDouble();

        assertEquals(primarySeconds(A.distanceKmTo(B) + B.distanceKmTo(C)), seconds, 0.5);
        assertTrue(seconds > primarySeconds(A.distanceKmTo(C)), "the road is longer than the straight line");
    }

    /** A house 100 m off the road: the straight bits to and from the road are added at 20 km/h. */
    @Test
    void theStraightBitsToAndFromTheRoadAreAdded() {
        RoadTravelTime travel = new RoadTravelTime(lShape(), noIncidents(), NIGHT);
        Location offRoad = new Location(LAT, LON - 0.0009);                    // about 100 m west of A

        double onRoad = travel.seconds(A, C, false).getAsDouble();
        double withLeg = travel.seconds(offRoad, C, false).getAsDouble();

        assertEquals(offRoad.distanceKmTo(A) * 1000 / RoadTravelTime.SNAP_LEG_METRES_PER_SECOND, withLeg - onRoad, 1e-6);
    }

    /** The traffic is taken at the clock's "now": the evening peak is slower than the night. */
    @Test
    void rushHourIsSlowerThanNight() {
        double night = new RoadTravelTime(lShape(), noIncidents(), NIGHT).seconds(A, C, false).getAsDouble();
        double peak = new RoadTravelTime(lShape(), noIncidents(), PEAK).seconds(A, C, false).getAsDouble();

        assertEquals(night / 0.4, peak, 1);                                      // primary roads at 0.4 at 18:00
    }

    @Test
    void aSirenIsFaster() {
        RoadTravelTime travel = new RoadTravelTime(lShape(), noIncidents(), PEAK);

        assertTrue(travel.seconds(A, C, true).getAsDouble() < travel.seconds(A, C, false).getAsDouble());
    }

    /** Closing the only road between B and C leaves no way there: empty, which the engine knows how to handle. */
    @Test
    void aClosureOnTheOnlyRoadGivesNoTime() {
        TrafficModel traffic = noIncidents();
        traffic.add(Incident.closure("cut", new Location(LAT + 0.01, LON + 0.005), 150));

        OptionalDouble seconds = new RoadTravelTime(lShape(), traffic, NIGHT).seconds(A, C, true);

        assertTrue(seconds.isEmpty());
    }

    /** load() reads a graph file written by GraphFile and answers the same. */
    @Test
    void aGraphLoadedFromAFileAnswersTheSame(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("l.graph");
        try (OutputStream out = Files.newOutputStream(file)) {
            GraphFile.write(lShape(), out);
        }

        RoadTravelTime loaded = RoadTravelTime.load(file, noIncidents(), NIGHT);

        assertEquals(new RoadTravelTime(lShape(), noIncidents(), NIGHT).seconds(A, C, false).getAsDouble(),
                loaded.seconds(A, C, false).getAsDouble(), 1e-6);
        assertEquals(3, loaded.graph().nodeCount());
    }

    @Test
    void missingArgumentsAreRefused() {
        RoadTravelTime travel = new RoadTravelTime(lShape(), noIncidents(), NIGHT);

        assertThrows(IllegalArgumentException.class, () -> travel.seconds(null, C, false));
        assertThrows(IllegalArgumentException.class, () -> travel.seconds(A, null, false));
        assertThrows(IllegalArgumentException.class, () -> new RoadTravelTime(null, noIncidents(), NIGHT));
        assertThrows(IllegalArgumentException.class, () -> new RoadTravelTime(lShape(), null, NIGHT));
        assertThrows(IllegalArgumentException.class, () -> new RoadTravelTime(lShape(), noIncidents(), null));
    }
}

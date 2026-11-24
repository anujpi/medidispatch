package com.medidispatch.service.api;

import com.medidispatch.core.domain.Location;
import com.medidispatch.routing.GraphBuilder;
import com.medidispatch.routing.Incident;
import com.medidispatch.routing.RoadClass;
import com.medidispatch.routing.RoadTravelTime;
import com.medidispatch.routing.TrafficModel;
import com.medidispatch.routing.TrafficProfile;
import com.medidispatch.service.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The route endpoint on real roads (the Spring tests run on straight lines): plain JUnit, the controller called
 * directly with a three-node L-shaped road A - B - C.
 */
class RouteControllerTest {

    private static final Clock NIGHT = Clock.fixed(Instant.parse("2026-11-23T21:30:00Z"), ZoneOffset.UTC);

    private final TrafficModel traffic = new TrafficModel(TrafficProfile.bangaloreWeekday());
    private final RouteController controller = new RouteController(new RoadTravelTime(lShape(), traffic, NIGHT), traffic);

    private static com.medidispatch.routing.RoadGraph lShape() {
        GraphBuilder b = new GraphBuilder();
        b.addNode(0, 12.97, 77.59);
        b.addNode(1, 12.98, 77.59);
        b.addNode(2, 12.98, 77.60);
        b.addRoad(0, 1, RoadClass.PRIMARY, true, true);
        b.addRoad(1, 2, RoadClass.PRIMARY, true, true);
        return b.build();
    }

    @Test
    void theRouteFollowsTheRoads() {
        RouteController.RouteInfo route = controller.route(12.97, 77.59, 12.98, 77.60, true);

        assertEquals(3, route.points().size());
        assertArrayEquals(new double[]{12.98, 77.59}, route.points().get(1));
        double roadMetres = (new Location(12.97, 77.59).distanceKmTo(new Location(12.98, 77.59))
                + new Location(12.98, 77.59).distanceKmTo(new Location(12.98, 77.60))) * 1000;
        assertEquals(roadMetres, route.metres(), 1);
        assertTrue(route.seconds() > 0);
    }

    @Test
    void anIncidentAddedOverTheApiIsDrivenAround() {
        controller.addIncident(new IncidentRequest("cut", new Coordinates(12.98, 77.595), 150.0, 0.0));

        assertThrows(NotFoundException.class, () -> controller.route(12.97, 77.59, 12.98, 77.60, true));
        assertEquals(1, controller.incidents().size());

        controller.removeIncident("cut");

        assertEquals(3, controller.route(12.97, 77.59, 12.98, 77.60, true).points().size());
        assertEquals(java.util.List.<Incident>of(), controller.incidents());
    }
}

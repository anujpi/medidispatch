package com.medidispatch.service.api;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.engine.StraightLineTravelTime;
import com.medidispatch.core.port.TravelTime;
import com.medidispatch.routing.Drive;
import com.medidispatch.routing.Incident;
import com.medidispatch.routing.RoadTravelTime;
import com.medidispatch.routing.TrafficModel;
import com.medidispatch.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * HTTP endpoints for the roads: the way from one point to another (for a crew's navigation and for drawing on a map),
 * and the incidents on the roads (jams and closures) that the routing drives around.
 *
 * <p>The service is the only place that knows the roads: the simulator and the map ask here instead of loading the
 * road graph themselves, the way a real crew phone would ask for navigation. Routes are not stored: each call plans
 * from scratch in the traffic of that moment.
 *
 * <p>Without the road graph (the tests, or a missing file) the answer is the straight line at
 * {@link StraightLineTravelTime#SPEED_KMH}, so callers never need a second code path.
 */
// ponytail: incidents live only in memory (lost on restart) and nobody may add them yet but anyone who can reach the
// service; they go to the database and behind a login with the security piece.
@RestController
@RequestMapping("/api")
public class RouteController {

    private final TravelTime travel;
    private final TrafficModel traffic;

    /**
     * @param travel  the travel times the engine uses (by road when the graph is there)
     * @param traffic rush hours and incidents, the same model the road travel times use
     */
    public RouteController(TravelTime travel, TrafficModel traffic) {
        this.travel = travel;
        this.traffic = traffic;
    }

    /**
     * {@code GET /api/route?fromLat=&fromLon=&toLat=&toLon=&emergency=true}: the fastest way right now.
     * 200 with the points; 404 if closures leave no way through; 400 for a missing or impossible coordinate.
     *
     * @param fromLat   start latitude
     * @param fromLon   start longitude
     * @param toLat     end latitude
     * @param toLon     end longitude
     * @param emergency {@code true} with the siren on (default {@code false})
     * @return the way, its time and length
     */
    @GetMapping("/route")
    public RouteInfo route(@RequestParam double fromLat, @RequestParam double fromLon, @RequestParam double toLat,
                           @RequestParam double toLon, @RequestParam(defaultValue = "false") boolean emergency) {
        Location from = new Location(fromLat, fromLon);
        Location to = new Location(toLat, toLon);
        Optional<Drive> drive;
        if (travel instanceof RoadTravelTime roads) {
            drive = roads.drive(from, to, emergency);
        } else {
            double seconds = travel.seconds(from, to, emergency).orElseThrow();
            drive = Optional.of(new Drive(List.of(from, to), seconds, from.distanceKmTo(to) * 1000));
        }
        if (drive.isEmpty()) {
            throw new NotFoundException("No way by road from " + from + " to " + to + " right now (closures)");
        }
        return RouteInfo.from(drive.get());
    }

    /**
     * {@code POST /api/incidents}: a jam or closure on the roads. 201; an existing id is replaced (so a jam can be
     * made worse or eased); 400 for a bad body. Routes planned from now on drive around or through it slowly; trips
     * already on their way keep their route.
     *
     * @param request where, how big, how slow
     */
    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public void addIncident(@RequestBody IncidentRequest request) {
        traffic.add(request.toIncident());
    }

    /**
     * {@code GET /api/incidents}: every incident, sorted by id.
     *
     * @return the incidents
     */
    @GetMapping("/incidents")
    public List<Incident> incidents() {
        return traffic.incidents();
    }

    /**
     * {@code DELETE /api/incidents/{id}}: the road is clear again. 204, also for an id that is not there (safe to
     * repeat).
     *
     * @param id the incident
     */
    @DeleteMapping("/incidents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeIncident(@PathVariable String id) {
        traffic.remove(id);
    }

    /**
     * The answer to a route question.
     *
     * @param seconds how long the drive takes, in the traffic right now
     * @param metres  how far it is
     * @param points  the way as {@code [latitude, longitude]} pairs, start first (pairs, not objects: a cross-city
     *                route has over a thousand points)
     */
    public record RouteInfo(double seconds, double metres, List<double[]> points) {

        static RouteInfo from(Drive drive) {
            List<double[]> points = new ArrayList<>(drive.points().size());
            for (Location p : drive.points()) {
                points.add(new double[]{p.latitude(), p.longitude()});
            }
            return new RouteInfo(drive.seconds(), drive.metres(), points);
        }
    }
}

package com.medidispatch.routing;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traffic for the router: a {@link TrafficProfile} (rush hours by road type) plus the incidents on the roads right
 * now. Live traffic is paid data; this is a model, and real data could later feed the same {@link CostModel}.
 * <p>
 * {@link #at} gives a {@code CostModel} frozen at one moment, for one kind of vehicle:
 * <ul>
 *   <li>the eight road classes' speeds are worked out once (not per road piece);</li>
 *   <li><b>emergency</b> (siren on): traffic makes way, so {@code min(traffic speed x 1.3, free-flow x 1.2)}: faster
 *       than traffic, never flying down a lane; a jam still slows it (nowhere to pull over);</li>
 *   <li>the incidents are copied, so one search sees one consistent picture even if an incident is added meanwhile;
 *       a road piece is affected when its midpoint is inside the circle; overlapping jams multiply; a closure makes the
 *       road cost infinite, and the router skips it;</li>
 *   <li>A*'s top speed is the fastest free-flow speed (x 1.2 for emergencies); traffic only slows, so the estimate
 *       stays safe.</li>
 * </ul>
 * Incidents can be added and removed from any thread while other threads route.
 */
// ponytail: the traffic at the departure time is used for the whole route; fully time-dependent routing (the factor
// changing as the trip goes on) is the upgrade if long trips across rush-hour boundaries ever matter.
public final class TrafficModel {

    /** Bangalore's time zone (UTC+5:30, no daylight saving): the profile's hours are local. */
    public static final ZoneId BANGALORE = ZoneId.of("Asia/Kolkata");

    /** With a siren, traffic makes way: this much faster than the traffic around. */
    static final double SIREN_TRAFFIC_BOOST = 1.3;

    /** ...but never more than this much faster than the road's free-flow speed. */
    static final double SIREN_CAP = 1.2;

    private static final double METRES_PER_DEGREE = 111_320;  // of latitude; longitude shrinks with cos(latitude)

    private final TrafficProfile profile;
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    /**
     * @param profile the rush-hour curve
     * @throws IllegalArgumentException if it is missing
     */
    public TrafficModel(TrafficProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        this.profile = profile;
    }

    /**
     * Adds an incident, or replaces the one with the same id.
     *
     * @param incident the incident
     * @throws IllegalArgumentException if it is missing
     */
    public void add(Incident incident) {
        if (incident == null) {
            throw new IllegalArgumentException("incident is required");
        }
        incidents.put(incident.id(), incident);
    }

    /**
     * @param id the incident to clear; unknown ids are ignored
     */
    public void remove(String id) {
        incidents.remove(id);
    }

    /** @return the current incidents, sorted by id */
    public List<Incident> incidents() {
        List<Incident> all = new ArrayList<>(incidents.values());
        all.sort((a, b) -> a.id().compareTo(b.id()));
        return all;
    }

    /**
     * @param when      the departure moment (converted to Bangalore time)
     * @param emergency {@code true} for a vehicle with its siren on
     * @return the road costs at that moment
     */
    public CostModel at(Instant when, boolean emergency) {
        return at(LocalTime.ofInstant(when, BANGALORE), emergency);
    }

    /**
     * @param time      the local departure time in Bangalore
     * @param emergency {@code true} for a vehicle with its siren on
     * @return the road costs at that time
     */
    public CostModel at(LocalTime time, boolean emergency) {
        RoadClass[] classes = RoadClass.values();
        double[] speed = new double[classes.length];          // metres per second, per road class
        double fastest = 0;
        for (RoadClass c : classes) {
            double free = c.speedKmh() / 3.6;
            double traffic = free * profile.factor(c, time);
            speed[c.ordinal()] = emergency ? Math.min(traffic * SIREN_TRAFFIC_BOOST, free * SIREN_CAP) : traffic;
            fastest = Math.max(fastest, free);
        }
        List<Area> areas = new ArrayList<>();
        for (Incident i : incidents.values()) {
            areas.add(Area.of(i));
        }
        return new Frozen(speed, List.copyOf(areas), fastest * (emergency ? SIREN_CAP : 1));
    }

    // an incident's circle, with a bounding box in degrees (1% larger) so most roads are rejected by 4 comparisons
    private record Area(double lat, double lon, double radiusMetres, double factor,
                        double minLat, double maxLat, double minLon, double maxLon) {

        static Area of(Incident i) {
            double lat = i.centre().latitude();
            double lon = i.centre().longitude();
            double dLat = i.radiusMetres() / METRES_PER_DEGREE * 1.01;
            double dLon = i.radiusMetres() / (METRES_PER_DEGREE * Math.cos(Math.toRadians(lat))) * 1.01;
            return new Area(lat, lon, i.radiusMetres(), i.speedFactor(), lat - dLat, lat + dLat, lon - dLon, lon + dLon);
        }

        boolean contains(double pLat, double pLon) {
            if (pLat < minLat || pLat > maxLat || pLon < minLon || pLon > maxLon) {
                return false;
            }
            return Router.metres(lat, lon, pLat, pLon) <= radiusMetres;
        }
    }

    // the road costs at one moment: immutable, safe to share between threads
    private record Frozen(double[] speed, List<Area> areas, double maxSpeed) implements CostModel {

        @Override
        public double seconds(RoadGraph g, int from, int edge) {
            double metresPerSecond = speed[g.roadClass(edge).ordinal()];
            if (!areas.isEmpty()) {
                int to = g.target(edge);
                double midLat = (g.latitude(from) + g.latitude(to)) / 2;   // road pieces are short: plenty precise
                double midLon = (g.longitude(from) + g.longitude(to)) / 2;
                for (Area a : areas) {
                    if (a.contains(midLat, midLon)) {
                        if (a.factor() == 0) {
                            return Double.POSITIVE_INFINITY;  // closed
                        }
                        metresPerSecond *= a.factor();         // after the siren rule: a jam slows ambulances too
                    }
                }
            }
            return g.length(edge) / metresPerSecond;
        }

        @Override
        public double maxSpeedMetresPerSecond() {
            return maxSpeed;
        }
    }
}

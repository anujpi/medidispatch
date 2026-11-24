package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A fake unit (ambulance, nurse or doctor) that drives to its patient.
 * <p>
 * When it gets a trip it waits {@link #ACK_DELAY} before setting off (the crew answering), then drives: in a
 * straight line at its type's speed, or, once it is given a route ({@link #follow}), along the route's points at the
 * route's own average speed, so it arrives when the router said it would. Each trip comes with a {@link Behaviour}: most trips go normally, but a unit can
 * never answer, break down halfway (stops moving, keeps reporting) or lose its signal halfway (keeps moving,
 * stops reporting). The engine is expected to notice the last three on its own.
 * <p>
 * A trip can have a second leg ({@link #goTo(Location)}: the patient on board, to a hospital). A unit with a home
 * (its base) drives back there after a trip ({@link #headHome()}), reporting its position on the way; a unit without
 * a home (the in-memory simulator) parks where the trip ended.
 */
final class SimulatedUnit {

    /** How a unit behaves on one trip. */
    enum Behaviour {
        /** Answers, drives, arrives. */
        NORMAL,
        /** Never answers, so the engine's acknowledgement timeout must catch it. */
        NEVER_ACKNOWLEDGES,
        /** Stops halfway but keeps reporting the same spot, so the stall check must catch it. */
        BREAKS_DOWN,
        /** Keeps driving but stops reporting halfway, so the signal check must catch it. */
        LOSES_SIGNAL
    }

    /** What happened to the unit during one step. */
    enum TickResult { NOTHING, ACKNOWLEDGED, ARRIVED }

    /** How long a crew takes to answer a new trip. */
    static final Duration ACK_DELAY = Duration.ofSeconds(20);

    private final String id;
    private final ResourceType type;
    private final double speedKmPerHour;
    private final Location home;                   // its base; null: parks where a trip ends
    private Location position;
    private boolean goingHome;

    // the way ahead (trip or drive home): the points still to reach, in order; the last one is the destination
    private List<Location> waypoints = List.of();
    private int next;                              // the waypoint being driven to
    private double restKm;                         // length of the way AFTER waypoints[next]
    private double legSpeedKmPerHour;              // this leg's speed: the type's, or the route's average

    // the current trip; assignmentId is null when the unit is free
    private String assignmentId;
    private Location target;
    private Behaviour behaviour;
    private double tripKm;
    private Duration waited;
    private boolean acknowledged;
    private boolean arrived;
    private boolean broken;
    private boolean silent;

    /**
     * Creates a free unit with no home: after a trip it stays where it is.
     *
     * @param id       unit id, also used in the engine
     * @param type     what kind of unit it is
     * @param position where it starts
     */
    SimulatedUnit(String id, ResourceType type, Location position) {
        this(id, type, position, null);
    }

    /**
     * Creates a free unit.
     *
     * @param id       unit id, also used in the engine
     * @param type     what kind of unit it is
     * @param position where it starts
     * @param home     its base, where it drives back to after a trip; {@code null} for none
     */
    SimulatedUnit(String id, ResourceType type, Location position, Location home) {
        this.id = id;
        this.home = home;
        this.type = type;
        this.position = position;
        this.speedKmPerHour = switch (type) {
            case AMBULANCE_ALS, AMBULANCE_BLS -> 50;
            case NURSE, DOCTOR, COURIER -> 30;
        };
    }

    /**
     * Gives the unit a new trip.
     *
     * @param assignmentId the engine's id for the trip
     * @param target       where the patient is
     * @param behaviour    how the unit will behave on this trip
     */
    void assign(String assignmentId, Location target, Behaviour behaviour) {
        this.assignmentId = assignmentId;
        this.target = target;
        this.behaviour = behaviour;
        driveStraight(target);
        this.tripKm = position.distanceKmTo(target);
        this.waited = Duration.ZERO;
        this.acknowledged = false;
        this.arrived = false;
        this.broken = false;
        this.silent = false;
        this.goingHome = false;                       // a new trip replaces the drive home
    }

    /**
     * The second leg of the current trip: the crew drives on with the patient (to a hospital). The trip stays the
     * same and was answered already; the unit reports its position again and arrives a second time. No planned
     * failure on this leg: the dispatch service cannot fail a trip that is carrying a patient yet.
     *
     * @param destination where to drive
     * @throws IllegalStateException if the unit has no trip
     */
    void goTo(Location destination) {
        if (assignmentId == null) {
            throw new IllegalStateException(id + " has no trip to continue");
        }
        this.target = destination;
        this.behaviour = Behaviour.NORMAL;
        driveStraight(destination);
        this.tripKm = position.distanceKmTo(destination);
        this.arrived = false;
        this.broken = false;
        this.silent = false;
    }

    /** A free unit with a home starts driving back there; otherwise nothing happens. */
    void headHome() {
        if (assignmentId == null && home != null && !position.equals(home)) {
            goingHome = true;
            driveStraight(home);
        }
    }

    /**
     * Drives the current leg (to the patient, to the hospital, or home) along a route instead of the straight line,
     * at the route's average speed. The route should start about where the unit is; it ends exactly at the leg's
     * destination (its last point is replaced by it, so a rounded number cannot make the unit miss).
     *
     * @param points  the route, start first, at least two points
     * @param seconds how long the route takes; 0 or less keeps the type's speed
     * @throws IllegalArgumentException if there are fewer than two points
     * @throws IllegalStateException    if the unit is not driving anywhere (free and not going home)
     */
    void follow(List<Location> points, double seconds) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("a route has at least two points");
        }
        Location destination = goingHome ? home : target;
        if (destination == null || (assignmentId == null && !goingHome)) {
            throw new IllegalStateException(id + " is not driving anywhere");
        }
        List<Location> way = new ArrayList<>(points.subList(1, points.size()));
        way.set(way.size() - 1, destination);
        double km = position.distanceKmTo(way.getFirst());
        for (int i = 1; i < way.size(); i++) {
            km += way.get(i - 1).distanceKmTo(way.get(i));
        }
        setWay(way);
        if (assignmentId != null) {
            tripKm = km;                                  // halfway is half the road, not half the straight line
        }
        if (seconds > 0 && km > 0) {
            legSpeedKmPerHour = km / (seconds / 3600);
        }
    }

    private void driveStraight(Location destination) {
        setWay(List.of(destination));
    }

    private void setWay(List<Location> way) {
        waypoints = List.copyOf(way);
        next = 0;
        restKm = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            restKm += waypoints.get(i - 1).distanceKmTo(waypoints.get(i));
        }
        legSpeedKmPerHour = speedKmPerHour;
    }

    // moves along the waypoints by up to km; true when the last one is reached
    private boolean advance(double km) {
        while (next < waypoints.size()) {
            Location to = waypoints.get(next);
            double d = position.distanceKmTo(to);
            if (d > km) {
                position = CityMap.towards(position, to, km);
                return false;
            }
            position = to;
            km -= d;
            next++;
            if (next < waypoints.size()) {
                restKm -= to.distanceKmTo(waypoints.get(next));
            }
        }
        return true;
    }

    // how far is still to go along the way
    private double remainingKm() {
        return next < waypoints.size() ? position.distanceKmTo(waypoints.get(next)) + Math.max(0, restKm) : 0;
    }

    /**
     * Moves the unit forward by one step of simulated time.
     *
     * @param dt how much time passes
     * @return {@code ACKNOWLEDGED} when the crew answers, {@code ARRIVED} when it reaches the patient (and can
     *         still report it), otherwise {@code NOTHING}
     */
    TickResult tick(Duration dt) {
        if (assignmentId == null && goingHome) {
            if (advance(legSpeedKmPerHour * dt.toMillis() / 3_600_000.0)) {
                goingHome = false;                        // back at base
            }
            return TickResult.NOTHING;
        }
        if (assignmentId == null || arrived) {
            return TickResult.NOTHING;                        // free, or already on scene
        }
        if (!acknowledged) {
            if (behaviour == Behaviour.NEVER_ACKNOWLEDGES) {
                return TickResult.NOTHING;
            }
            waited = waited.plus(dt);
            if (waited.compareTo(ACK_DELAY) < 0) {
                return TickResult.NOTHING;
            }
            acknowledged = true;
            return TickResult.ACKNOWLEDGED;
        }
        if (broken) {
            return TickResult.NOTHING;                        // stuck where it broke down
        }
        boolean there = advance(legSpeedKmPerHour * dt.toMillis() / 3_600_000.0);
        if (remainingKm() <= tripKm / 2) {                    // halfway: a planned problem starts here
            if (behaviour == Behaviour.BREAKS_DOWN) {
                broken = true;
            }
            if (behaviour == Behaviour.LOSES_SIGNAL) {
                silent = true;
            }
        }
        if (there) {
            arrived = true;
            return silent ? TickResult.NOTHING : TickResult.ARRIVED;   // without signal, nobody hears it arrive
        }
        return TickResult.NOTHING;
    }

    /** The trip has ended (done, cancelled, timed out or failed): the unit parks where it is. */
    void free() {
        assignmentId = null;
        target = null;
        behaviour = null;
        waypoints = List.of();
    }

    /**
     * Says whether the unit sends a position report this step: while driving on a trip with a working signal (a
     * broken-down unit keeps reporting the same spot), and while driving home.
     *
     * @return {@code true} if the unit reports its position now
     */
    boolean reportsPosition() {
        return (assignmentId != null && acknowledged && !arrived && !silent) || goingHome;
    }

    boolean busy()              { return assignmentId != null; }
    String assignmentId()       { return assignmentId; }
    String id()                 { return id; }
    ResourceType type()         { return type; }
    Location position()         { return position; }
    boolean broken()            { return broken; }
    boolean silent()            { return silent; }
    boolean acknowledged()      { return acknowledged; }
    boolean arrived()           { return arrived; }
    Location target()           { return target; }       // null when free
    boolean goingHome()         { return goingHome; }
    Location home()             { return home; }        // null: no base
    double legSpeedKmPerHour()  { return legSpeedKmPerHour; }
}

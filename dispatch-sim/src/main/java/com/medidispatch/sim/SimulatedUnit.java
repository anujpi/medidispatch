package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceType;

import java.time.Duration;

/**
 * A fake unit (ambulance, nurse or doctor) that drives to its patient.
 * <p>
 * When it gets a trip it waits {@link #ACK_DELAY} before setting off (the crew answering), then moves in a
 * straight line at its speed. Each trip comes with a {@link Behaviour}: most trips go normally, but a unit can
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
        this.tripKm = position.distanceKmTo(destination);
        this.arrived = false;
        this.broken = false;
        this.silent = false;
    }

    /** A free unit with a home starts driving back there; otherwise nothing happens. */
    void headHome() {
        if (assignmentId == null && home != null && !position.equals(home)) {
            goingHome = true;
        }
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
            position = CityMap.towards(position, home, speedKmPerHour * dt.toMillis() / 3_600_000.0);
            if (position.equals(home)) {
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
        double km = speedKmPerHour * dt.toMillis() / 3_600_000.0;
        position = CityMap.towards(position, target, km);
        if (position.distanceKmTo(target) <= tripKm / 2) {    // halfway: a planned problem starts here
            if (behaviour == Behaviour.BREAKS_DOWN) {
                broken = true;
            }
            if (behaviour == Behaviour.LOSES_SIGNAL) {
                silent = true;
            }
        }
        if (position.equals(target)) {
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
}

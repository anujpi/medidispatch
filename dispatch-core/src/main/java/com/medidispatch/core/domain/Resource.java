package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * A unit that can be sent to patients: an ambulance, a nurse or a courier.
 * <p>
 * Besides its status and position, a resource remembers when it last reported ({@link #lastSeenAt()})
 * and when it last really moved ({@link #lastMovedAt()}). The trip monitor uses these to spot units
 * that have lost signal or are stuck.
 */
public class Resource {
    /** Position changes up to this distance (20 m) are GPS jitter, not movement. */
    public static final double MOVEMENT_THRESHOLD_KM = 0.02;
    private final String id;
    private  ResourceStatus status = ResourceStatus.AVAILABLE;
    private final ResourceType type;
    private Location location;
    private Location movedFrom;
    private Instant lastSeenAt;
    private Instant lastMovedAt;
    private final String baseId;

    /**
     * Creates a unit with no base that is {@link ResourceStatus#AVAILABLE} and has not reported yet.
     *
     * @param id       unique unit id; never blank
     * @param type     what kind of unit it is
     * @param location where the unit is now
     * @throws IllegalArgumentException if the id is blank or any argument is missing
     */
    public Resource(String id, ResourceType type, Location location) {
        this(id, type, location, null);
    }

    /**
     * Creates a unit that is {@link ResourceStatus#AVAILABLE} and has not reported yet.
     *
     * @param id       unique unit id; never blank
     * @param type     what kind of unit it is
     * @param location where the unit is now
     * @param baseId   the id of the hospital or clinic the unit belongs to; {@code null} for none, never blank.
     *                 Whether that facility exists is checked where units are registered, not here
     * @throws IllegalArgumentException if the id or baseId is blank or a required argument is missing
     */
    public Resource(String id, ResourceType type, Location location, String baseId) {
        if(id == null || id.isBlank()) throw new IllegalArgumentException("Id must be provided");
        if(type == null) throw new IllegalArgumentException("Type must be provided");
        if(location == null) throw new IllegalArgumentException("Location must be provided");
        if(baseId != null && baseId.isBlank()) throw new IllegalArgumentException("baseId cannot be blank");
        this.id = id;
        this.type = type;
        this.location = location;
        this.movedFrom= location;
        this.baseId = baseId;
    }

    /**
     * Rebuilds a unit that already exists (read back from storage), with every field as it was saved.
     * <p>
     * For storage adapters only. The constructor always starts a unit as AVAILABLE and never seen, and
     * {@link #transitionTo(ResourceStatus)} only allows legal moves, so neither can rebuild a stored unit. This
     * sets the fields directly; from then on the restored unit obeys every rule as usual.
     *
     * @param id          unique unit id; never blank
     * @param type        what kind of unit it is
     * @param baseId      the facility it belongs to; {@code null} for none
     * @param status      its saved status
     * @param location    where it is
     * @param movedFrom   the last position that counted as movement ({@link #reportLocation})
     * @param lastSeenAt  when it last reported; {@code null} if never
     * @param lastMovedAt when it last really moved; {@code null} if never
     * @return the rebuilt unit
     * @throws IllegalArgumentException if the id is blank or a required argument is missing
     */
    public static Resource restore(String id, ResourceType type, String baseId, ResourceStatus status,
                                   Location location, Location movedFrom, Instant lastSeenAt, Instant lastMovedAt) {
        if (status == null) throw new IllegalArgumentException("Status must be provided");
        if (movedFrom == null) throw new IllegalArgumentException("movedFrom must be provided");
        Resource r = new Resource(id, type, location, baseId);
        r.status = status;
        r.movedFrom = movedFrom;
        r.lastSeenAt = lastSeenAt;
        r.lastMovedAt = lastMovedAt;
        return r;
    }

    /**
     * Moves the unit to a new status, if the move is allowed.
     *
     * @param next the status to move to
     * @throws IllegalArgumentException if {@code next} is missing
     * @throws IllegalStateException    if the move is not allowed; the status is left unchanged
     * @see ResourceStatus#canTransitionTo(ResourceStatus)
     */
    public void transitionTo(ResourceStatus next){
        if(next == null){
            throw new IllegalArgumentException("Next status is required");
        }
        if(!status.canTransitionTo(next)){
            throw new IllegalStateException("Cannot go from "+ status + " to " + next);
        }
        status = next;
    }
    /**
     * Places the unit somewhere without treating it as a live report (setup, manual corrections).
     * Also resets the movement reference point, so the next live report is measured from here.
     *
     * @param newLocation the new position
     * @throws IllegalArgumentException if {@code newLocation} is missing
     */
    public void updateLocation(Location newLocation){
        if(newLocation == null){
            throw new IllegalArgumentException("Location cant be empty");
        }
        location = newLocation;
        movedFrom = newLocation;
    }
    /**
     * Records a live position report from the unit.
     * <p>
     * Every report updates the position and {@link #lastSeenAt()}. {@link #lastMovedAt()} only changes
     * when the unit is more than {@link #MOVEMENT_THRESHOLD_KM} from the last position that counted as
     * movement, so GPS jitter is ignored but many small steps still add up.
     *
     * @param where the reported position
     * @param at    when the report was received (passed in, so this class needs no clock)
     * @throws IllegalArgumentException if either argument is missing; nothing is changed
     */
    public void reportLocation(Location where, Instant at){
        if(where == null || at == null){
            throw new IllegalArgumentException("where and at are required");
        }
        lastSeenAt = at;
        location = where;
        // compare with the last position that counted, so many small steps add up
        if(where.distanceKmTo(movedFrom) > MOVEMENT_THRESHOLD_KM){
            lastMovedAt = at;
            movedFrom = where;
        }
    }
    /**
     * Says whether the unit can be sent right now.
     *
     * @return {@code true} if the status is {@link ResourceStatus#AVAILABLE}
     */
    public boolean isAvailable(){
        return status == ResourceStatus.AVAILABLE;
    }
    public String id() { return id; }
    public ResourceStatus status() { return status; }
    public ResourceType type() { return type; }
    public Location location() { return location; }

    /**
     * The last position that counted as movement: the next live report is measured from here. Storage adapters
     * need it so a restarted service keeps spotting stuck units correctly.
     */
    public Location movedFrom() { return movedFrom; }

    /** When the unit last sent any position report; empty until its first report. */
    public Optional<Instant> lastSeenAt()  { return Optional.ofNullable(lastSeenAt); }

    /** When the unit last really moved (beyond GPS jitter); empty until it first moves. */
    public Optional<Instant> lastMovedAt() { return Optional.ofNullable(lastMovedAt); }

    /** The id of the hospital or clinic the unit belongs to; empty if it has no base. */
    public Optional<String> baseId() { return Optional.ofNullable(baseId); }
}

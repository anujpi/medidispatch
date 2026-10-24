package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.Optional;

public class Resource {
    public static final double MOVEMENT_THRESHOLD_KM = 0.02;
    private final String id;
    private  ResourceStatus status = ResourceStatus.AVAILABLE;
    private final ResourceType type;
    private Location location;
    private Location movedFrom;
    private Instant lastSeenAt;
    private Instant lastMovedAt;

    public Resource(String id, ResourceType type, Location location) {

        if(id == null || id.isBlank()) throw new IllegalArgumentException("Id must be provided");
        if(type == null) throw new IllegalArgumentException("Type must be provided");
        if(location == null) throw new IllegalArgumentException("Location must be provided");
        this.id = id;
        this.type = type;
        this.location = location;
        this.movedFrom= location;
    }
    public void transitionTo(ResourceStatus next){
        if(next == null){
            throw new IllegalArgumentException("Next status is required");
        }
        if(!status.canTransitionTo(next)){
            throw new IllegalStateException("Cannot go from "+ status + " to " + next);
        }
        status = next;
    }
    public void updateLocation(Location newLocation){
        if(newLocation == null){
            throw new IllegalArgumentException("Location cant be empty");
        }
        location = newLocation;
        movedFrom = newLocation;
    }
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
    public boolean isAvailable(){
        return status == ResourceStatus.AVAILABLE;
    }
    public String id() { return id; }
    public ResourceStatus status() { return status; }
    public ResourceType type() { return type; }
    public Location location() { return location; }
    public Optional<Instant> lastSeenAt()  { return Optional.ofNullable(lastSeenAt); }
    public Optional<Instant> lastMovedAt() { return Optional.ofNullable(lastMovedAt); }
}

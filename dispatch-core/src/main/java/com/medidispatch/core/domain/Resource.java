package com.medidispatch.core.domain;

public class Resource {
    private final String id;
    private  ResourceStatus status = ResourceStatus.AVAILABLE;
    private final ResourceType type;
    private Location location;

    public Resource(String id, ResourceType type, Location location) {
        if(id == null || id.isBlank()) throw new IllegalArgumentException("Id must be provided");
        if(type == null) throw new IllegalArgumentException("Type must be provided");
        if(location == null) throw new IllegalArgumentException("Location must be provided");
        this.id = id;
        this.type = type;
        this.location = location;
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
    }
    public boolean isAvailable(){
        return status == ResourceStatus.AVAILABLE;
    }
    public String id() { return id; }
    public ResourceStatus status() { return status; }
    public ResourceType type() { return type; }
    public Location location() { return location; }

}

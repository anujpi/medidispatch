package com.medidispatch.core.domain;

public class Patient {
    private final String id;
    private Location location;
    public Patient(String id,Location location){
        if(id == null || id.isBlank()){
            throw new IllegalArgumentException("id is required");
        }
        if(location == null){
            throw new IllegalArgumentException("location is required");
        }
        this.id = id;
        this.location = location;
    }
    public void updateLocation(Location newLocation){
        if(newLocation == null){
            throw new IllegalArgumentException("location is required");
        }
        location = newLocation;
    }
    public String id(){ return id; }
    public Location location() { return location ;}
}

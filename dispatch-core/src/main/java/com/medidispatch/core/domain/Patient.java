package com.medidispatch.core.domain;

/**
 * A registered, monitored person whose vitals the engine watches.
 * <p>
 * Kept small on purpose: identity and current location. Clinical context (conditions, baseline vitals)
 * will be added later for context-aware scoring and response decisions.
 */
public class Patient {
    private final String id;
    private Location location;

    /**
     * Creates a patient.
     *
     * @param id       unique patient id; never blank
     * @param location where the patient is now
     * @throws IllegalArgumentException if the id is blank or either argument is missing
     */
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
    /**
     * Moves the patient, for example when they travel.
     *
     * @param newLocation the new position
     * @throws IllegalArgumentException if {@code newLocation} is missing
     */
    public void updateLocation(Location newLocation){
        if(newLocation == null){
            throw new IllegalArgumentException("location is required");
        }
        location = newLocation;
    }
    public String id(){ return id; }
    public Location location() { return location ;}
}

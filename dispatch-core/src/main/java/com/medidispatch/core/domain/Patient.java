package com.medidispatch.core.domain;

import java.util.Optional;

/**
 * A registered, monitored person whose vitals the engine watches.
 * <p>
 * The engine only needs the identity and location. Who the person is ({@link PersonDetails}) and whom to call
 * ({@link #emergencyContact()}) are for the people running the system: the control room and the hospital. Both are
 * optional here, so the in-memory simulator can stay anonymous; the service's API requires the details.
 * Clinical context (conditions, baseline vitals) will be added later for context-aware scoring.
 */
public class Patient {
    private final String id;
    private Location location;
    private final PersonDetails details;
    private final Contact emergencyContact;

    /**
     * Creates an anonymous patient (no details, no emergency contact).
     *
     * @param id       unique patient id; never blank
     * @param location where the patient is now
     * @throws IllegalArgumentException if the id is blank or either argument is missing
     */
    public Patient(String id, Location location) {
        this(id, location, null, null);
    }

    /**
     * Creates a patient.
     *
     * @param id               unique patient id; never blank
     * @param location         where the patient is now
     * @param details          name, age and sex; {@code null} if unknown
     * @param emergencyContact whom to call (family, a neighbour); {@code null} if none is on file
     * @throws IllegalArgumentException if the id is blank or the location is missing
     */
    public Patient(String id, Location location, PersonDetails details, Contact emergencyContact) {
        if(id == null || id.isBlank()){
            throw new IllegalArgumentException("id is required");
        }
        if(location == null){
            throw new IllegalArgumentException("location is required");
        }
        this.id = id;
        this.location = location;
        this.details = details;
        this.emergencyContact = emergencyContact;
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

    /** Name, age and sex; empty for an anonymous patient. */
    public Optional<PersonDetails> details() { return Optional.ofNullable(details); }

    /** Whom to call about this patient; empty if none is on file. */
    public Optional<Contact> emergencyContact() { return Optional.ofNullable(emergencyContact); }
}

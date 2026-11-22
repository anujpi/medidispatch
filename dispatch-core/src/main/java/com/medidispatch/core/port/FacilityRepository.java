package com.medidispatch.core.port;

import com.medidispatch.core.domain.Facility;

import java.util.List;
import java.util.Optional;

/**
 * Port: the registry of hospitals and clinics.
 * <p>
 * Small and slow-changing (tens of facilities in a city), so the destination choice simply reads them all.
 */
public interface FacilityRepository {

    /**
     * Stores a facility, replacing any facility with the same id (how diversion is switched on and off).
     *
     * @param facility the facility to store
     * @throws IllegalArgumentException if {@code facility} is missing
     */
    void save(Facility facility);

    /**
     * Looks up a facility by id.
     *
     * @param id the facility id
     * @return the facility, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is missing
     */
    Optional<Facility> findById(String id);

    /**
     * @return every facility (hospitals and clinics), sorted by id; a list the caller may change
     */
    List<Facility> findAll();
}

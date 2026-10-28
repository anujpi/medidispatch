package com.medidispatch.core.port;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Port: where units are stored and found by location.
 * <p>
 * The "near" searches are the important part: a real adapter (Redis GEO, PostGIS) answers them with a
 * spatial index, so only nearby units are ever looked at. Results are always nearest first, with the
 * unit id as the tie-break, and the edge of the radius is included.
 */
public interface ResourceRepository {

    /**
     * Stores a unit, replacing any unit with the same id. Call it after every change to a unit.
     *
     * @param resource the unit to store
     * @throws IllegalArgumentException if {@code resource} is missing
     */
    void save(Resource resource);

    /**
     * Looks up a unit by id.
     *
     * @param id the unit id
     * @return the unit, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is missing
     */
    Optional<Resource> findById(String id);

    /**
     * Finds free units of the given types around a point.
     *
     * @param center   the point to search around, usually the patient
     * @param radiusKm how far to look, in kilometres; must be positive
     * @param types    the unit types wanted; an empty set finds nothing
     * @return {@code AVAILABLE} units within the radius, nearest first
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    List<Resource> findAvailableNear(Location center, double radiusKm, Set<ResourceType> types);

    /**
     * Finds busy units of the given types around a point; used to look for a unit to preempt.
     *
     * @param center   the point to search around, usually the patient
     * @param radiusKm how far to look, in kilometres; must be positive
     * @param types    the unit types wanted; an empty set finds nothing
     * @return {@code ASSIGNED} units within the radius, nearest first
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    List<Resource> findAssignedNear(Location center,double radiusKm,Set<ResourceType> types);
}

package com.medidispatch.core.port;

import com.medidispatch.core.domain.Assignment;

import java.util.List;
import java.util.Optional;

/**
 * Port: where trips are stored. "Active" means not in a terminal status (not done, cancelled, timed out
 * or failed).
 */
public interface AssignmentRepository {

    /**
     * Stores a trip, replacing any trip with the same id. Call it after every change to a trip.
     *
     * @param assignment the trip to store
     */
    void save(Assignment assignment);

    /**
     * Looks up a trip by id.
     *
     * @param id the trip id
     * @return the trip, or empty if there is none with that id
     */
    Optional<Assignment> findById(String id);

    /**
     * Lists the trips currently serving a case. A case can have several at once (for example an ambulance
     * and a first responder).
     *
     * @param caseId the case
     * @return the active trips, empty if no unit is currently assigned to the case
     */
    List<Assignment> findActiveByCaseId(String caseId);

    /**
     * Finds the trip a unit is currently on. A unit is on at most one trip at a time.
     *
     * @param resourceId the unit
     * @return the unit's active trip, or empty if it is not on one
     */
    Optional<Assignment> findActiveByResourceId(String resourceId);

    /**
     * Lists every trip that has not ended; used by the monitors that check trips regularly.
     *
     * @return all active trips
     */
    List<Assignment> findActive();
}

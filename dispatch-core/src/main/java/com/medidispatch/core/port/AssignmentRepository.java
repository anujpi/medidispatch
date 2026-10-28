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
     * Finds the trip currently serving a case. A case has at most one active trip.
     *
     * @param caseId the case
     * @return the active trip, or empty if no unit is currently assigned to the case
     */
    Optional<Assignment> findActiveByCaseId(String caseId);

    /**
     * Lists every trip that has not ended; used by the monitors that check trips regularly.
     *
     * @return all active trips
     */
    List<Assignment> findActive();
}

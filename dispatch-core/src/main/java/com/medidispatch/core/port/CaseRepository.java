package com.medidispatch.core.port;

import com.medidispatch.core.domain.Case;

import java.util.Optional;

/**
 * Port: where cases are stored. Postgres will implement it in the service layer.
 */
public interface CaseRepository {

    /**
     * Stores a case, replacing any case with the same id. Call it after every change to a case.
     *
     * @param c the case to store
     */
    void save(Case c);

    /**
     * Looks up a case by id.
     *
     * @param id the case id
     * @return the case, or empty if there is none with that id
     */
    Optional<Case> findById(String id);

    /**
     * Finds the patient's open case, if any. A patient has at most one case that is not closed or cancelled.
     *
     * @param patientId the patient
     * @return the open case, or empty if the patient has none
     */
    Optional<Case> findOpenByPatientId(String patientId);
}

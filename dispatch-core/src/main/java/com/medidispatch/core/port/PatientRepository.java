package com.medidispatch.core.port;

import com.medidispatch.core.domain.Patient;

import java.util.Optional;

/**
 * Port: where registered patients are stored. Postgres will implement it in the service layer.
 */
public interface PatientRepository {

    /**
     * Stores a patient, replacing any patient with the same id.
     *
     * @param patient the patient to store
     */
    void save(Patient patient);

    /**
     * Looks up a patient by id.
     *
     * @param id the patient id
     * @return the patient, or empty if there is none with that id
     */
    Optional<Patient> findById(String id);
}

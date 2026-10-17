package com.medidispatch.core.port;

import com.medidispatch.core.domain.Patient;

import java.util.Optional;

public interface PatientRepository {
    void save(Patient patient);
    Optional<Patient> findById(String id);
}

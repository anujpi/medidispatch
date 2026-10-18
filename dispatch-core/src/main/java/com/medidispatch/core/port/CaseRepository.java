package com.medidispatch.core.port;

import com.medidispatch.core.domain.Case;

import java.util.Optional;

public interface CaseRepository {
    void save(Case c);
    Optional<Case> findById(String id);
    Optional<Case> findOpenByPatientId(String patientId);
}

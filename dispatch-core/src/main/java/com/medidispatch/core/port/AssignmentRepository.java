package com.medidispatch.core.port;

import com.medidispatch.core.domain.Assignment;

import java.util.Optional;

public interface AssignmentRepository {
    void save(Assignment assignment);
    Optional<Assignment> findById(String id);
    Optional<Assignment> findActiveByCaseId(String caseId);
}

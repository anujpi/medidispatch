package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link CaseRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * {@link #findOpenByPatientId(String)} scans every case; a database adapter would use an index instead.
 */
public class InMemoryCaseRepository implements CaseRepository {

    private final Map<String,Case> byId = new HashMap<>();

    @Override
    public void save(Case c) {
        if(c == null){
            throw new IllegalArgumentException("case is required");
        }
        byId.put(c.id(),c);
    }

    @Override
    public Optional<Case> findById(String id) {
        if(id == null){
            throw new IllegalArgumentException("id is required");
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Case> findOpenByPatientId(String patientId) {
        if(patientId == null){
            throw new IllegalArgumentException("PatientId is required");
        }
        for(Case c: byId.values()){
            if(c.patientId().equals(patientId) && !c.status().isTerminal()){
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}

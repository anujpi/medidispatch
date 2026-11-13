package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link CaseRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * {@link #findOpenByPatientId(String)} uses a small index (patient to their latest saved open case), so it stays
 * instant however many closed cases pile up, for example in an endless simulation.
 */
public class InMemoryCaseRepository implements CaseRepository {

    private final Map<String,Case> byId = new HashMap<>();
    private final Map<String,String> openCaseIdByPatient = new HashMap<>();

    @Override
    public void save(Case c) {
        if(c == null){
            throw new IllegalArgumentException("case is required");
        }
        byId.put(c.id(),c);
        if(!c.status().isTerminal()){
            openCaseIdByPatient.put(c.patientId(), c.id());
        }
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
        String caseId = openCaseIdByPatient.get(patientId);
        Case c = caseId == null ? null : byId.get(caseId);
        if(c == null || c.status().isTerminal()){          // the case has been closed since it was indexed
            return Optional.empty();
        }
        return Optional.of(c);
    }
}

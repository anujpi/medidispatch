package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.port.PatientRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryPatientRepository implements PatientRepository {
    private final Map<String,Patient> byId = new HashMap<>();
    @Override
    public void save(Patient patient) {
        if(patient == null){
            throw new IllegalArgumentException("patient is required");
        }
        byId.put(patient.id(),patient);
    }

    @Override
    public Optional<Patient> findById(String id) {
        if(id == null){
            throw new IllegalArgumentException("id is required");
        }
        return Optional.ofNullable(byId.get(id));
    }
}

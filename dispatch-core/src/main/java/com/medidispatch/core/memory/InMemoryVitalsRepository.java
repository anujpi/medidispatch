package com.medidispatch.core.memory;

import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.port.VitalsRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link VitalsRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * Keeps every reading per patient. {@link #latestFor(String)} looks through that patient's readings, so it
 * does not depend on the order they were saved in.
 */
public class InMemoryVitalsRepository implements VitalsRepository {

    private final Map<String, List<VitalsReading>> byPatient = new HashMap<>();

    @Override
    public void save(VitalsReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("reading is required");
        }
        List<VitalsReading> readings = byPatient.get(reading.patientId());
        if (readings == null) {                               // first reading for this patient
            readings = new ArrayList<>();
            byPatient.put(reading.patientId(), readings);
        }
        readings.add(reading);
    }

    @Override
    public Optional<VitalsReading> latestFor(String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        List<VitalsReading> readings = byPatient.get(patientId);
        if (readings == null) {
            return Optional.empty();
        }
        VitalsReading latest = null;
        for (VitalsReading r : readings) {
            if (latest == null || r.timeStamp().isAfter(latest.timeStamp())) {
                latest = r;
            }
        }
        return Optional.ofNullable(latest);
    }
}

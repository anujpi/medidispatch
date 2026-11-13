package com.medidispatch.core.memory;

import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.port.VitalsRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link VitalsRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * Keeps only the latest reading per patient (by time, not by save order), because the port only ever asks for
 * the latest. That keeps memory flat and lookups instant, even in an endless simulation; a database adapter would
 * keep the full history.
 */
public class InMemoryVitalsRepository implements VitalsRepository {

    private final Map<String, VitalsReading> latestByPatient = new HashMap<>();

    @Override
    public void save(VitalsReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("reading is required");
        }
        VitalsReading latest = latestByPatient.get(reading.patientId());
        if (latest == null || reading.timeStamp().isAfter(latest.timeStamp())) {
            latestByPatient.put(reading.patientId(), reading);
        }
    }

    @Override
    public Optional<VitalsReading> latestFor(String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        return Optional.ofNullable(latestByPatient.get(patientId));
    }
}

package com.medidispatch.core.port;

import com.medidispatch.core.domain.VitalsReading;

import java.util.Optional;

/**
 * Port: where vitals readings are stored.
 * <p>
 * The engine uses the stored history to tell a new reading from a repeat or a late arrival, and trend and
 * baseline scoring will read it later. A time-series database will implement it in the service layer.
 */
public interface VitalsRepository {

    /**
     * Stores a reading.
     *
     * @param reading the reading to store
     * @throws IllegalArgumentException if {@code reading} is missing
     */
    void save(VitalsReading reading);

    /**
     * Finds the patient's newest stored reading, by the time it was taken.
     *
     * @param patientId the patient
     * @return the newest reading, or empty if none is stored for that patient
     * @throws IllegalArgumentException if {@code patientId} is missing
     */
    Optional<VitalsReading> latestFor(String patientId);
}

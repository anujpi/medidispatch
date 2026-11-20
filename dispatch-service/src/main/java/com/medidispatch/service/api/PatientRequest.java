package com.medidispatch.service.api;

/**
 * Body of {@code POST /api/patients}: {@code {"id": "p1", "home": {"latitude": 28.61, "longitude": 77.20}}}.
 *
 * @param id   the patient id, chosen by the caller, so a repeated request is recognised
 * @param home where the patient lives
 */
public record PatientRequest(String id, Coordinates home) {

    /**
     * @throws IllegalArgumentException if the home is missing (a blank id is refused by the engine)
     */
    public PatientRequest {
        if (home == null) {
            throw new IllegalArgumentException("home is required");
        }
    }
}

package com.medidispatch.service.api;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Sex;

/**
 * Body of {@code POST /api/patients}:
 * {@code {"id": "p1", "home": {"latitude": 12.93, "longitude": 77.62}, "name": "Asha Rao", "age": 68,
 * "sex": "FEMALE", "emergencyContact": {"name": "Vikram Rao", "phone": "+91 98450 12345"}}}.
 *
 * @param id               the patient id, chosen by the caller, so a repeated request is recognised
 * @param home             where the patient lives
 * @param name             the patient's name; required
 * @param age              in years, 0 to 120; required
 * @param sex              FEMALE, MALE or OTHER; required
 * @param emergencyContact whom to call; optional
 */
public record PatientRequest(String id, Coordinates home, String name, Integer age, Sex sex,
                             Contact emergencyContact) {

    /**
     * @throws IllegalArgumentException if the home or age is missing, or the name, age or sex is not valid (a blank
     *                                  id is refused by the engine)
     */
    public PatientRequest {
        if (home == null) {
            throw new IllegalArgumentException("home is required");
        }
        if (age == null) {
            throw new IllegalArgumentException("age is required");
        }
        new PersonDetails(name, age, sex);                    // checks name, age and sex now, while Jackson reads
    }

    /** @return the domain patient */
    Patient toPatient() {
        return new Patient(id, home.toLocation(), new PersonDetails(name, age, sex), emergencyContact);
    }
}

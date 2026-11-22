package com.medidispatch.service;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Sex;

/**
 * A patient's full record, for {@code GET /api/patients/{id}}. It holds the emergency contact, which is why the live
 * feed never carries this record: family phone numbers have no business on every screen every second.
 *
 * @param id               the patient id
 * @param latitude         where the patient is
 * @param longitude        where the patient is
 * @param name             the patient's name, or {@code null} for an anonymous patient
 * @param age              the patient's age, or {@code null}
 * @param sex              the patient's sex, or {@code null}
 * @param emergencyContact whom to call about the patient, or {@code null} if none is on file
 */
public record PatientInfo(String id, double latitude, double longitude, String name, Integer age, Sex sex,
                          Contact emergencyContact) {

    /**
     * @param p the engine's patient
     * @return the snapshot
     */
    static PatientInfo from(Patient p) {
        PersonDetails who = p.details().orElse(null);
        return new PatientInfo(p.id(), p.location().latitude(), p.location().longitude(),
                who == null ? null : who.name(), who == null ? null : who.age(), who == null ? null : who.sex(),
                p.emergencyContact().orElse(null));
    }
}

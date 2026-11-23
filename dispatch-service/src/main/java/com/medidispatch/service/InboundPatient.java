package com.medidispatch.service;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.Sex;
import com.medidispatch.core.domain.VitalsReading;

import java.util.Set;

/**
 * One line of a hospital's pre-alert: a patient on the way in, so the team can get ready before the ambulance
 * arrives (a stroke team for a stroke, a resuscitation bay for a CRITICAL patient).
 *
 * @param tripId       the trip carrying the patient
 * @param caseId       the patient's case
 * @param patientId    the patient
 * @param patientName  the patient's name, or {@code null} for an anonymous patient
 * @param age          the patient's age, or {@code null}
 * @param sex          the patient's sex, or {@code null}
 * @param band         how urgent the patient is right now (it keeps updating during transport)
 * @param needs        what the crew said the patient needs when transport started
 * @param unitId       the ambulance
 * @param unitType     AMBULANCE_ALS or AMBULANCE_BLS
 * @param latitude     where the ambulance is now
 * @param longitude    where the ambulance is now
 * @param distanceKm   straight-line distance from the ambulance to the hospital
 * @param etaMinutes   minutes until arrival, rounded up (see {@link DispatchService#inboundTo})
 * @param latestVitals the patient's newest reading, or {@code null} if there is none
 */
public record InboundPatient(String tripId, String caseId, String patientId, String patientName, Integer age,
                             Sex sex, RiskBand band, Set<Capability> needs,
                             String unitId, ResourceType unitType, double latitude, double longitude,
                             double distanceKm, long etaMinutes, VitalsReading latestVitals) {

    /**
     * Copies the fields out of live engine objects. Call it while holding the {@link DispatchService} lock.
     *
     * @param trip         the TRANSPORTING trip
     * @param c            its case
     * @param patient      its patient (name, age and sex for the hospital)
     * @param unit         its ambulance
     * @param distanceKm   ambulance to hospital
     * @param etaMinutes   minutes until arrival
     * @param latestVitals the newest reading, or {@code null}
     * @return the snapshot
     */
    static InboundPatient from(Assignment trip, Case c, Patient patient, Resource unit, double distanceKm,
                               long etaMinutes, VitalsReading latestVitals) {
        PersonDetails who = patient.details().orElse(null);
        return new InboundPatient(trip.id(), c.id(), c.patientId(), who == null ? null : who.name(),
                who == null ? null : who.age(), who == null ? null : who.sex(), c.band(), trip.needs(), unit.id(),
                unit.type(), unit.location().latitude(), unit.location().longitude(), distanceKm, etaMinutes, latestVitals);
    }
}

package com.medidispatch.service;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.RiskBand;

import java.time.Instant;

/**
 * A read-only snapshot of a case, safe to keep, send as JSON or hand to another thread.
 *
 * @param id        the case id
 * @param patientId the patient the case is about
 * @param status    where the case is in its life cycle
 * @param band      how serious it is
 * @param openedAt  when it was opened
 * @param latitude  where the patient is, so a map can draw the case
 * @param longitude where the patient is
 */
public record CaseInfo(String id, String patientId, CaseStatus status, RiskBand band, Instant openedAt,
                       double latitude, double longitude) {

    /**
     * Copies the fields out of a live engine case and its patient. Call it while holding the
     * {@link DispatchService} lock.
     *
     * @param c       the engine's case
     * @param patient the case's patient (a Case only stores the patient's id, not where they are)
     * @return the snapshot
     */
    static CaseInfo from(Case c, Patient patient) {
        return new CaseInfo(c.id(), c.patientId(), c.status(), c.band(), c.openedAt(),
                patient.location().latitude(), patient.location().longitude());
    }
}

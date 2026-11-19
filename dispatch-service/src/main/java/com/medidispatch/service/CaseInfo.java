package com.medidispatch.service;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
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
 */
public record CaseInfo(String id, String patientId, CaseStatus status, RiskBand band, Instant openedAt) {

    /**
     * Copies the fields out of a live engine case. Call it while holding the {@link DispatchService} lock.
     *
     * @param c the engine's case
     * @return the snapshot
     */
    static CaseInfo from(Case c) {
        return new CaseInfo(c.id(), c.patientId(), c.status(), c.band(), c.openedAt());
    }
}

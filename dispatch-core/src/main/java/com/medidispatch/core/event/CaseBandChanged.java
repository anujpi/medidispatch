package com.medidispatch.core.event;

import com.medidispatch.core.domain.RiskBand;

import java.time.Instant;

/**
 * A case's urgency changed because the patient got worse or better. Only published when the band
 * really changed.
 *
 * @param caseId     the case that changed
 * @param patientId  the patient the case is about
 * @param from       the band before
 * @param to         the band after
 * @param occurredAt when it happened
 */
public record CaseBandChanged(String caseId, String patientId,
                              RiskBand from, RiskBand to,
                              Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or any field is missing
     */
    public CaseBandChanged {
        Checks.id(caseId, "caseId");
        Checks.id(patientId, "patientId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

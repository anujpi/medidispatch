package com.medidispatch.core.event;

import com.medidispatch.core.domain.CaseStatus;

import java.time.Instant;

/**
 * A case moved from one status to another, for example from ALERTED to RESPONDING.
 *
 * @param caseId     the case that changed
 * @param patientId  the patient the case is about
 * @param from       the status before
 * @param to         the status after
 * @param occurredAt when it happened
 */
public record CaseStatusChanged(String caseId, String patientId, CaseStatus from, CaseStatus to, Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or any field is missing
     */
    public CaseStatusChanged{
        Checks.id(caseId, "caseId");
        Checks.id(patientId, "patientId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

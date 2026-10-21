package com.medidispatch.core.event;

import com.medidispatch.core.domain.CaseStatus;

import java.time.Instant;

public record CaseStatusChanged(String caseId, String patientId, CaseStatus from, CaseStatus to, Instant occurredAt) implements DomainEvent {
    public CaseStatusChanged{
        Checks.id(caseId, "caseId");
        Checks.id(patientId, "patientId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

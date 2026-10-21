package com.medidispatch.core.event;

import com.medidispatch.core.domain.RiskBand;

import java.time.Instant;

public record CaseBandChanged(String caseId, String patientId,
                              RiskBand from, RiskBand to,
                              Instant occurredAt) implements DomainEvent {
    public CaseBandChanged {
        Checks.id(caseId, "caseId");
        Checks.id(patientId, "patientId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}
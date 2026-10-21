package com.medidispatch.core.event;

import java.time.Instant;

public record UnitAssigned(String assignmentId, String caseId, String resourceId,
                           Instant occurredAt) implements DomainEvent {
    public UnitAssigned {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(occurredAt, "occurredAt");
    }
}
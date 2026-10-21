package com.medidispatch.core.event;

import com.medidispatch.core.domain.AssignmentStatus;

import java.time.Instant;

public record AssignmentStatusChanged(String assignmentId, String caseId, String resourceId,
                                      AssignmentStatus from, AssignmentStatus to,
                                      Instant occurredAt) implements DomainEvent {
    public AssignmentStatusChanged {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}
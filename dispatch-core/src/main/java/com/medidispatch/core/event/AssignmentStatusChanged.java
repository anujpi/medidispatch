package com.medidispatch.core.event;

import com.medidispatch.core.domain.AssignmentStatus;

import java.time.Instant;

/**
 * A trip moved from one status to another, for example from EN_ROUTE to ARRIVED, or ended as
 * CANCELLED, TIMED_OUT or FAILED.
 *
 * @param assignmentId the trip that changed
 * @param caseId       the case the trip serves
 * @param resourceId   the unit on the trip
 * @param from         the status before
 * @param to           the status after
 * @param occurredAt   when it happened
 */
public record AssignmentStatusChanged(String assignmentId, String caseId, String resourceId,
                                      AssignmentStatus from, AssignmentStatus to,
                                      Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or any field is missing
     */
    public AssignmentStatusChanged {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

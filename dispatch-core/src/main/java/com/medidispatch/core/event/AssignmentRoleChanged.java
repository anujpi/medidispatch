package com.medidispatch.core.event;

import com.medidispatch.core.domain.AssignmentRole;

import java.time.Instant;

/**
 * A trip's role changed: the unit was the case's main response, and is now a first responder because a more
 * capable unit is needed. The trip itself keeps going.
 *
 * @param assignmentId the trip that changed
 * @param caseId       the case the trip serves
 * @param resourceId   the unit on the trip
 * @param from         the role before
 * @param to           the role after
 * @param occurredAt   when it happened
 */
public record AssignmentRoleChanged(String assignmentId, String caseId, String resourceId,
                                    AssignmentRole from, AssignmentRole to,
                                    Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or any field is missing
     */
    public AssignmentRoleChanged {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(from, "from");
        Checks.notNull(to, "to");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

package com.medidispatch.core.event;

import com.medidispatch.core.domain.AssignmentRole;

import java.time.Instant;

/**
 * A unit was sent to a case and a new trip started.
 *
 * @param assignmentId the new trip
 * @param caseId       the case the unit is sent to
 * @param resourceId   the unit that was sent
 * @param role         whether it is the main response or a first responder
 * @param occurredAt   when it happened
 */
public record UnitAssigned(String assignmentId, String caseId, String resourceId,
                           AssignmentRole role, Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or the role or time is missing
     */
    public UnitAssigned {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(role, "role");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

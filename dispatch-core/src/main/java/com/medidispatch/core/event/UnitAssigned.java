package com.medidispatch.core.event;

import java.time.Instant;

/**
 * A unit was sent to a case and a new trip started.
 *
 * @param assignmentId the new trip
 * @param caseId       the case the unit is sent to
 * @param resourceId   the unit that was sent
 * @param occurredAt   when it happened
 */
public record UnitAssigned(String assignmentId, String caseId, String resourceId,
                           Instant occurredAt) implements DomainEvent {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if an id is blank or the time is missing
     */
    public UnitAssigned {
        Checks.id(assignmentId, "assignmentId");
        Checks.id(caseId, "caseId");
        Checks.id(resourceId, "resourceId");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

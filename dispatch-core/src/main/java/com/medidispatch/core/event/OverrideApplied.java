package com.medidispatch.core.event;

import java.time.Instant;

/**
 * A dispatcher overrode the engine. Announced after the change itself (and its own events, such as the trip being
 * cancelled), so the record of "who decided this" sits next to what happened. Who did it comes with the login (SEC).
 *
 * @param kind       what was overridden
 * @param caseId     the case it was about; null when a free unit was taken out of service
 * @param unitId     the unit it was about; null when a case was cancelled
 * @param reason     the dispatcher's reason, for the record
 * @param occurredAt when it happened
 */
public record OverrideApplied(OverrideKind kind, String caseId, String unitId, String reason,
                              Instant occurredAt) implements DomainEvent {
    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException if the kind, the reason or the time is missing
     */
    public OverrideApplied {
        Checks.notNull(kind, "kind");
        Checks.id(reason, "reason");
        Checks.notNull(occurredAt, "occurredAt");
    }
}

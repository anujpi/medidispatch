package com.medidispatch.service;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;

import java.time.Instant;

/**
 * A read-only snapshot of a trip: one unit sent to one case. The engine calls this an "assignment"; outside the
 * engine we say "trip" and "unit", the words crews and the control room use.
 *
 * @param id         the trip id
 * @param caseId     the case the unit was sent to
 * @param unitId     the unit that was sent
 * @param role       PRIMARY (the main unit) or SUPPORT (an extra one)
 * @param status     where the trip is in its life cycle
 * @param assignedAt when the unit was sent
 */
public record TripInfo(String id, String caseId, String unitId, AssignmentRole role, AssignmentStatus status,
                       Instant assignedAt) {

    /**
     * Copies the fields out of a live engine assignment. Call it while holding the {@link DispatchService} lock.
     *
     * @param a the engine's assignment
     * @return the snapshot
     */
    static TripInfo from(Assignment a) {
        return new TripInfo(a.id(), a.caseId(), a.resourceId(), a.role(), a.status(), a.assignedAt());
    }
}

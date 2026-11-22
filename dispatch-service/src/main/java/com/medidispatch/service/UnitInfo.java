package com.medidispatch.service;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;

import java.time.Instant;

/**
 * A read-only snapshot of a unit. The position is flattened into two numbers so the JSON stays simple.
 *
 * @param id         the unit id
 * @param type       what kind of unit it is
 * @param status     AVAILABLE, ASSIGNED or OFFLINE
 * @param latitude   last known latitude
 * @param longitude  last known longitude
 * @param lastSeenAt when the unit last reported its position, or {@code null} if it never has
 * @param baseId     the hospital or clinic the unit belongs to, or {@code null} if it has no base
 * @param driver     who drives it and their phone, or {@code null} if not on file
 */
public record UnitInfo(String id, ResourceType type, ResourceStatus status, double latitude, double longitude,
                       Instant lastSeenAt, String baseId, Contact driver) {

    /**
     * Copies the fields out of a live engine resource. Call it while holding the {@link DispatchService} lock.
     *
     * @param r the engine's resource
     * @return the snapshot
     */
    static UnitInfo from(Resource r) {
        // null rather than Optional: JSON has null, and records holding Optional are awkward to serialise.
        return new UnitInfo(r.id(), r.type(), r.status(), r.location().latitude(), r.location().longitude(),
                r.lastSeenAt().orElse(null), r.baseId().orElse(null), r.driver().orElse(null));
    }
}

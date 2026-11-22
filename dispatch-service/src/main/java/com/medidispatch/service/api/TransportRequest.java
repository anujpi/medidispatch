package com.medidispatch.service.api;

import com.medidispatch.core.domain.Capability;

import java.util.Set;

/**
 * Body of {@code POST /api/trips/{id}/transport}: {@code {"needs": ["STROKE"]}}.
 *
 * @param needs what the patient needs, as judged by the crew: CARDIAC, STROKE, TRAUMA, PAEDIATRIC (anything else is
 *              a 400). Missing or {@code null} means nothing special, so the crew can just tap "Transport"
 */
public record TransportRequest(Set<Capability> needs) {

    /** Turns a missing list into an empty one. */
    public TransportRequest {
        if (needs == null) {
            needs = Set.of();
        }
    }
}

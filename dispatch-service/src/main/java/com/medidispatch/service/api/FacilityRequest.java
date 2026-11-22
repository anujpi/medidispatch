package com.medidispatch.service.api;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;

import java.util.Set;

/**
 * Body of {@code POST /api/facilities}:
 * {@code {"id": "h-apollo", "name": "Apollo", "kind": "HOSPITAL", "position": {"latitude": 28.56, "longitude": 77.28},
 * "capabilities": ["STROKE", "CARDIAC"], "accepting": true, "phone": "+91 80 2345 6789"}}.
 *
 * @param id           the facility id, chosen by the operator
 * @param name         the name people know it by
 * @param kind         HOSPITAL or CLINIC; anything else is a 400
 * @param position     where it is
 * @param capabilities the specialist services it offers; missing means none (emergency department only, or a clinic)
 * @param accepting    whether its emergency department takes new patients; missing means {@code true} for a
 *                     hospital and {@code false} for a clinic
 * @param phone        how the control room reaches it; required
 */
public record FacilityRequest(String id, String name, FacilityKind kind, Coordinates position,
                              Set<Capability> capabilities, Boolean accepting, String phone) {

    /**
     * @throws IllegalArgumentException if the position, kind or phone is missing (blank id and name, and a phone
     *                                  that is not valid, are refused by the engine)
     */
    public FacilityRequest {
        if (position == null) {
            throw new IllegalArgumentException("position is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required (HOSPITAL or CLINIC)");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        if (capabilities == null) {
            capabilities = Set.of();
        }
        if (accepting == null) {
            accepting = kind == FacilityKind.HOSPITAL;
        }
    }

    /**
     * @return the domain facility; the engine checks the rest (for example, a clinic cannot accept patients)
     * @throws IllegalArgumentException if the engine refuses the values
     */
    Facility toFacility() {
        return new Facility(id, name, position.toLocation(), kind, capabilities, accepting, phone);
    }
}

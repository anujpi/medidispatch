package com.medidispatch.service.api;

import com.medidispatch.core.domain.ResourceType;

/**
 * Body of {@code POST /api/units}:
 * {@code {"id": "u1", "type": "AMBULANCE_ALS", "position": {"latitude": 28.62, "longitude": 77.21},
 * "baseId": "h-apollo"}}.
 *
 * @param id       the unit id, chosen by the caller
 * @param type     one of AMBULANCE_ALS, AMBULANCE_BLS, NURSE, COURIER, DOCTOR; anything else is a 400
 * @param position where the unit is now
 * @param baseId   optional: the hospital or clinic the unit belongs to; it must exist (else 400)
 */
public record UnitRequest(String id, ResourceType type, Coordinates position, String baseId) {

    /**
     * @throws IllegalArgumentException if the position is missing (blank id and missing type are refused by the
     *                                  engine)
     */
    public UnitRequest {
        if (position == null) {
            throw new IllegalArgumentException("position is required");
        }
    }
}

package com.medidispatch.service.api;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.ResourceType;

/**
 * Body of {@code POST /api/units}:
 * {@code {"id": "u1", "type": "AMBULANCE_ALS", "position": {"latitude": 28.62, "longitude": 77.21},
 * "baseId": "h-apollo", "driver": {"name": "Ravi Kumar", "phone": "+91 98450 12345"}}}.
 *
 * @param id       the unit id, chosen by the caller
 * @param type     one of AMBULANCE_ALS, AMBULANCE_BLS, NURSE, COURIER, DOCTOR; anything else is a 400
 * @param position where the unit is now
 * @param baseId   the hospital or clinic that owns the unit; required, and it must exist (else 400)
 * @param driver   who drives the unit and how to reach them; required
 */
public record UnitRequest(String id, ResourceType type, Coordinates position, String baseId, Contact driver) {

    /**
     * @throws IllegalArgumentException if the position, base or driver is missing (blank id and missing type are
     *                                  refused by the engine)
     */
    public UnitRequest {
        if (position == null) {
            throw new IllegalArgumentException("position is required");
        }
        // Every unit is run by someone: a real ambulance always belongs to a hospital or clinic.
        if (baseId == null || baseId.isBlank()) {
            throw new IllegalArgumentException("baseId is required: every unit belongs to a hospital or clinic");
        }
        if (driver == null) {
            throw new IllegalArgumentException("driver is required (name and phone)");
        }
    }
}

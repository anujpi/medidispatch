package com.medidispatch.service.api;

import com.medidispatch.routing.Incident;

/**
 * Body of {@code POST /api/incidents}:
 * {@code {"id": "silk-board", "position": {"latitude": 12.917, "longitude": 77.623}, "radiusMetres": 300,
 * "speedFactor": 0.2}}. A speed factor of 0 closes the roads inside.
 *
 * @param id           unique id; the same id again replaces the incident
 * @param position     the centre
 * @param radiusMetres how far it reaches
 * @param speedFactor  0 (closed) up to below 1
 */
public record IncidentRequest(String id, Coordinates position, Double radiusMetres, Double speedFactor) {

    /** @throws IllegalArgumentException if a value is missing (ranges are checked by {@link Incident}) */
    public IncidentRequest {
        if (position == null || radiusMetres == null || speedFactor == null) {
            throw new IllegalArgumentException("position, radiusMetres and speedFactor are required");
        }
    }

    /**
     * @return the incident
     * @throws IllegalArgumentException if a value is out of range
     */
    Incident toIncident() {
        return new Incident(id, position.toLocation(), radiusMetres, speedFactor);
    }
}

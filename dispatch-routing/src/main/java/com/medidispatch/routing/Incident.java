package com.medidispatch.routing;

import com.medidispatch.core.domain.Location;

/**
 * Something on the roads, as a circle: everything whose midpoint is inside drives at {@code speedFactor} of its usual
 * speed. An accident jam might be 300 m at 0.2; a closure is factor 0: impassable.
 * <p>
 * A circle, not a list of road pieces: it matches how incidents are reported ("accident near Silk Board"), a map can
 * draw it, and it does not depend on the graph's numbering, which changes whenever the map data is imported again.
 *
 * @param id           unique id; adding an incident with an existing id replaces it
 * @param centre       where it is
 * @param radiusMetres how far it reaches; above 0
 * @param speedFactor  0 (closed) up to below 1 (an incident that slows nothing is a mistake)
 */
public record Incident(String id, Location centre, double radiusMetres, double speedFactor) {

    /**
     * @throws IllegalArgumentException if a value is missing or out of range
     */
    public Incident {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (centre == null) {
            throw new IllegalArgumentException("centre is required");
        }
        if (!(radiusMetres > 0)) {
            throw new IllegalArgumentException("radius must be positive, got " + radiusMetres);
        }
        if (!(speedFactor >= 0 && speedFactor < 1)) {
            throw new IllegalArgumentException("speed factor must be 0 (closed) up to below 1, got " + speedFactor);
        }
    }

    /**
     * @param id           unique id
     * @param centre       where it is
     * @param radiusMetres how far it reaches
     * @return a road closure: nothing inside can be driven
     */
    public static Incident closure(String id, Location centre, double radiusMetres) {
        return new Incident(id, centre, radiusMetres, 0);
    }

    /** @return {@code true} if nothing inside can be driven */
    public boolean closed() {
        return speedFactor == 0;
    }
}

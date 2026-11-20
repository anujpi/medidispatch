package com.medidispatch.service.api;

import com.medidispatch.core.domain.Location;

/**
 * A position as it arrives in a request body: {@code {"latitude": 28.61, "longitude": 77.20}}.
 *
 * <p>Why not use the engine's {@link Location} directly? Location has {@code double} fields, and when a client
 * leaves a field out, the JSON reader fills a {@code double} with 0.0 without complaint: the unit would silently
 * appear at 0,0 in the ocean. Here the fields are {@code Double} (which can be null), and a missing one is refused.
 *
 * @param latitude  -90 to 90
 * @param longitude -180 to 180
 */
public record Coordinates(Double latitude, Double longitude) {

    /**
     * Refuses missing values. Thrown while the JSON is being read, so it reaches the client as 400 Bad Request.
     *
     * @throws IllegalArgumentException if either value is missing
     */
    public Coordinates {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("latitude and longitude are required");
        }
    }

    /**
     * @return the engine's location; Location itself checks the ranges
     * @throws IllegalArgumentException if a value is out of range
     */
    Location toLocation() {
        return new Location(latitude, longitude);
    }
}

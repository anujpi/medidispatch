package com.medidispatch.service.api;

/**
 * Body of {@code PUT /api/facilities/{id}/accepting}: {@code {"accepting": false}}.
 *
 * @param accepting {@code false} declares diversion (full: send patients elsewhere when possible); {@code true} ends it
 */
public record AcceptingRequest(Boolean accepting) {

    /** @throws IllegalArgumentException if the value is missing: guessing either way could misdirect ambulances */
    public AcceptingRequest {
        if (accepting == null) {
            throw new IllegalArgumentException("accepting is required (true or false)");
        }
    }
}

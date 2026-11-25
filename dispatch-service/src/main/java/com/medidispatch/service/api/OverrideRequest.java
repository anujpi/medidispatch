package com.medidispatch.service.api;

/**
 * The body of a dispatcher's override: the reason (always), and the unit (only when sending one).
 * {@code {"unitId": "als-7", "reason": "veteran crew, two minutes away"}}
 *
 * @param unitId the unit to send; only read by {@code POST /api/cases/{id}/send}
 * @param reason why, for the record; 1 to 200 characters
 */
public record OverrideRequest(String unitId, String reason) {
    /** The longest reason accepted, so the record stays a reason and not an essay. */
    public static final int MAX_REASON = 200;

    /**
     * Validates the request; a broken body is a 400 before anything is changed.
     *
     * @throws IllegalArgumentException if the reason is missing, blank or too long
     */
    public OverrideRequest {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("reason is at most " + MAX_REASON + " characters");
        }
    }
}

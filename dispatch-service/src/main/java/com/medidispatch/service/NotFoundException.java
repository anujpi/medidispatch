package com.medidispatch.service;

/**
 * Thrown when a request names a trip or unit that does not exist. Kept separate from
 * {@link IllegalStateException} ("it exists, but you can't do that now") so the web layer can answer
 * 404 for this one and 409 for the other.
 */
public class NotFoundException extends RuntimeException {

    /**
     * @param message what was not found, for example "Trip a-17 not found"
     */
    public NotFoundException(String message) {
        super(message);
    }
}

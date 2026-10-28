package com.medidispatch.core.event;

/**
 * Shared validation for the event records in this package, so every event rejects bad input the same way.
 * Package-private on purpose: it is a helper for events only.
 */
final class Checks {
    private Checks(){}

    /**
     * Rejects a missing or blank id.
     *
     * @param value the id to check
     * @param name  the field name, used in the error message
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    static void id(String value,String name){
        if(value == null || value.isBlank()){
            throw new IllegalArgumentException(name + "is required");
        }
    }

    /**
     * Rejects a missing value.
     *
     * @param value the value to check
     * @param name  the field name, used in the error message
     * @throws IllegalArgumentException if {@code value} is null
     */
    static void notNull(Object value,String name){
        if(value == null){
            throw new IllegalArgumentException(name +"is required");
        }
    }
}

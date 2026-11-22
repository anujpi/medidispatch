package com.medidispatch.core.domain;

/**
 * What kind of place a {@link Facility} is.
 */
public enum FacilityKind {
    /** Has an emergency department: patients can be taken there, and units can be based there. */
    HOSPITAL,
    /** A small clinic: units can be based there, but it never receives patients from an ambulance. */
    CLINIC
}

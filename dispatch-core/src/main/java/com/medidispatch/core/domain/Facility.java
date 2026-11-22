package com.medidispatch.core.domain;

import java.util.Set;

/**
 * A hospital or clinic: a place units are based at and, for hospitals, where patients are taken.
 * <p>
 * Immutable: a facility has no state machine, only facts that change now and then. "Changing" one means building a
 * new record (for example {@link #withAccepting(boolean)} when a hospital declares or ends diversion) and saving it
 * over the old one.
 * <p>
 * A clinic has no emergency department, so it never has capabilities and is never accepting. That rule is checked
 * here, so a clinic that claims a stroke unit cannot even be built.
 *
 * @param id           unique facility id; never blank
 * @param name         the name people know it by; never blank
 * @param location     where it is
 * @param kind         hospital or clinic
 * @param capabilities the specialist services it offers; empty means emergency department only (always empty for a
 *                     clinic). Stored as an unmodifiable copy, so changing the caller's set later changes nothing here
 * @param accepting    {@code true} if its emergency department takes new patients; {@code false} means it has
 *                     declared diversion (full), so ambulances should go elsewhere when they can. Always
 *                     {@code false} for a clinic
 */
public record Facility(String id, String name, Location location, FacilityKind kind,
                       Set<Capability> capabilities, boolean accepting) {

    /**
     * Validates the facility and copies its capabilities.
     *
     * @throws IllegalArgumentException if the id or name is blank, the location, kind or capabilities are missing,
     *                                  the capabilities contain {@code null}, or a clinic has capabilities or is
     *                                  accepting
     */
    public Facility {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (location == null) {
            throw new IllegalArgumentException("location is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities are required (empty means emergency department only)");
        }
        // Checked by hand: Set.copyOf would refuse a null too, but with a NullPointerException, and bad input in
        // the core is always an IllegalArgumentException.
        for (Capability c : capabilities) {
            if (c == null) {
                throw new IllegalArgumentException("capabilities cannot contain null");
            }
        }
        if (kind == FacilityKind.CLINIC && (!capabilities.isEmpty() || accepting)) {
            throw new IllegalArgumentException("a clinic has no emergency department: no capabilities, never accepting");
        }
        capabilities = Set.copyOf(capabilities);
    }

    /**
     * @param id           unique facility id
     * @param name         the name people know it by
     * @param location     where it is
     * @param capabilities the specialist services it offers; empty means emergency department only
     * @param accepting    whether its emergency department takes new patients
     * @return a hospital
     * @throws IllegalArgumentException as for the constructor
     */
    public static Facility hospital(String id, String name, Location location, Set<Capability> capabilities,
                                    boolean accepting) {
        return new Facility(id, name, location, FacilityKind.HOSPITAL, capabilities, accepting);
    }

    /**
     * @param id       unique facility id
     * @param name     the name people know it by
     * @param location where it is
     * @return a clinic: no capabilities, never accepting
     * @throws IllegalArgumentException as for the constructor
     */
    public static Facility clinic(String id, String name, Location location) {
        return new Facility(id, name, location, FacilityKind.CLINIC, Set.of(), false);
    }

    /** @return {@code true} if this is a hospital, so patients can be taken here */
    public boolean isHospital() {
        return kind == FacilityKind.HOSPITAL;
    }

    /**
     * Says whether patients who need these services can be taken here.
     *
     * @param needed the specialist services needed; an empty set means "any emergency department"
     * @return {@code true} if this is a hospital that offers all of them; always {@code false} for a clinic
     */
    public boolean canTreat(Set<Capability> needed) {
        return isHospital() && capabilities.containsAll(needed);
    }

    /**
     * @param nowAccepting whether the emergency department now takes new patients
     * @return a copy of this facility with only {@code accepting} changed
     * @throws IllegalArgumentException if this is a clinic and {@code nowAccepting} is {@code true}
     */
    public Facility withAccepting(boolean nowAccepting) {
        return new Facility(id, name, location, kind, capabilities, nowAccepting);
    }
}

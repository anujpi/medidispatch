package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class FacilityTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);

    private static Facility hospital(Set<Capability> capabilities) {
        return Facility.hospital("h1", "City Hospital", DELHI, capabilities, true);
    }

    private static Facility clinic() {
        return Facility.clinic("c1", "Lane Clinic", DELHI);
    }

    // ---- hospitals ----

    @Test
    void keepsWhatItWasGiven() {
        Facility h = Facility.hospital("h1", "City Hospital", DELHI, Set.of(Capability.STROKE), false);

        assertEquals("h1", h.id());
        assertEquals("City Hospital", h.name());
        assertEquals(DELHI, h.location());
        assertEquals(FacilityKind.HOSPITAL, h.kind());
        assertTrue(h.isHospital());
        assertEquals(Set.of(Capability.STROKE), h.capabilities());
        assertFalse(h.accepting());
    }

    /** The set is copied: changing the caller's set later changes nothing, and the copy cannot be changed. */
    @Test
    void capabilitiesAreAnUnmodifiableCopy() {
        Set<Capability> given = new HashSet<>(Set.of(Capability.CARDIAC));
        Facility h = hospital(given);

        given.add(Capability.STROKE);

        assertEquals(Set.of(Capability.CARDIAC), h.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> h.capabilities().add(Capability.TRAUMA));
    }

    /** Needing nothing special means any emergency department will do. */
    @Test
    void everyHospitalCanTreatWhenNothingSpecialIsNeeded() {
        assertTrue(hospital(Set.of()).canTreat(Set.of()));
    }

    @Test
    void canTreatOnlyWhatItOffers() {
        Facility strokeOnly = hospital(Set.of(Capability.STROKE));

        assertTrue(strokeOnly.canTreat(Set.of(Capability.STROKE)));
        assertFalse(strokeOnly.canTreat(Set.of(Capability.CARDIAC)));
    }

    /** An injured child needs both a trauma centre and paediatrics, in the same hospital. */
    @Test
    void needingTwoThingsRequiresBoth() {
        Set<Capability> injuredChild = Set.of(Capability.TRAUMA, Capability.PAEDIATRIC);

        assertTrue(hospital(Set.of(Capability.TRAUMA, Capability.PAEDIATRIC, Capability.CARDIAC)).canTreat(injuredChild));
        assertFalse(hospital(Set.of(Capability.TRAUMA)).canTreat(injuredChild));
    }

    @Test
    void withAcceptingChangesOnlyAccepting() {
        Facility open = hospital(Set.of(Capability.CARDIAC));

        Facility diverting = open.withAccepting(false);

        assertFalse(diverting.accepting());
        assertTrue(open.accepting());
        assertEquals(open.id(), diverting.id());
        assertEquals(open.name(), diverting.name());
        assertEquals(open.location(), diverting.location());
        assertEquals(open.kind(), diverting.kind());
        assertEquals(open.capabilities(), diverting.capabilities());
    }

    @Test
    void invalidValuesAreRejected() {
        FacilityKind h = FacilityKind.HOSPITAL;
        assertThrows(IllegalArgumentException.class, () -> new Facility(" ", "City", DELHI, h, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Facility(null, "City", DELHI, h, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Facility("h1", "", DELHI, h, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Facility("h1", "City", null, h, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Facility("h1", "City", DELHI, null, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Facility("h1", "City", DELHI, h, null, true));
    }

    @Test
    void aNullCapabilityIsRejected() {
        Set<Capability> withNull = new HashSet<>();
        withNull.add(Capability.STROKE);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> hospital(withNull));
    }

    // ---- phone ----

    @Test
    void aFacilityHasNoPhoneUntilOneIsGiven() {
        Facility h = hospital(Set.of());

        Facility withPhone = h.withPhone("+91 80 2345 6789");

        assertNull(h.phone());
        assertEquals("+91 80 2345 6789", withPhone.phone());
        assertEquals(h.id(), withPhone.id());
        assertEquals(h.accepting(), withPhone.accepting());
    }

    /** Switching diversion keeps the phone. */
    @Test
    void withAcceptingKeepsThePhone() {
        Facility h = hospital(Set.of()).withPhone("+91 80 2345 6789");

        assertEquals("+91 80 2345 6789", h.withAccepting(false).phone());
    }

    @Test
    void anInvalidPhoneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> hospital(Set.of()).withPhone("not a phone"));
    }

    // ---- clinics ----

    @Test
    void aClinicHasNoCapabilitiesAndIsNeverAccepting() {
        Facility c = clinic();

        assertEquals(FacilityKind.CLINIC, c.kind());
        assertFalse(c.isHospital());
        assertTrue(c.capabilities().isEmpty());
        assertFalse(c.accepting());
    }

    @Test
    void aClinicWithCapabilitiesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Facility("c1", "Lane Clinic", DELHI, FacilityKind.CLINIC, Set.of(Capability.CARDIAC), false));
    }

    @Test
    void anAcceptingClinicIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Facility("c1", "Lane Clinic", DELHI, FacilityKind.CLINIC, Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> clinic().withAccepting(true));
    }

    /** Not even "any emergency department": a clinic has none. */
    @Test
    void aClinicCanTreatNothing() {
        assertFalse(clinic().canTreat(Set.of()));
        assertFalse(clinic().canTreat(Set.of(Capability.CARDIAC)));
    }
}

package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PatientTest {

    private static final Location BANGALORE = new Location(12.9716, 77.5946);
    private static final Location CHENNAI = new Location(13.0827, 80.2707);

    // ---------- construction ----------

    @Test
    void newPatientHasTheGivenIdAndLocation() {
        Patient p = new Patient("p1", BANGALORE);

        assertEquals("p1", p.id());
        assertEquals(BANGALORE, p.location());
    }

    @Test
    void nullOrBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Patient(null, BANGALORE));
        assertThrows(IllegalArgumentException.class, () -> new Patient("  ", BANGALORE));
    }

    @Test
    void nullLocationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Patient("p1", null));
    }

    // ---------- location ----------

    @Test
    void locationCanBeUpdated() {
        Patient p = new Patient("p1", BANGALORE);

        p.updateLocation(CHENNAI);

        assertEquals(CHENNAI, p.location());
    }

    @Test
    void nullLocationUpdateIsRejectedAndTheOldLocationIsKept() {
        Patient p = new Patient("p1", BANGALORE);

        assertThrows(IllegalArgumentException.class, () -> p.updateLocation(null));

        assertEquals(BANGALORE, p.location());
    }

    // ---------- who the patient is ----------

    @Test
    void anAnonymousPatientHasNoDetailsAndNoEmergencyContact() {
        Patient p = new Patient("p1", BANGALORE);

        assertTrue(p.details().isEmpty());
        assertTrue(p.emergencyContact().isEmpty());
    }

    @Test
    void detailsAndEmergencyContactAreKept() {
        PersonDetails asha = new PersonDetails("Asha Rao", 68, Sex.FEMALE);
        Contact son = new Contact("Vikram Rao", "+91 98450 12345");

        Patient p = new Patient("p1", BANGALORE, asha, son);

        assertEquals(asha, p.details().orElseThrow());
        assertEquals(son, p.emergencyContact().orElseThrow());
    }

    /** The emergency contact is optional on its own: not everyone has one on file. */
    @Test
    void detailsWithoutAnEmergencyContactAreFine() {
        Patient p = new Patient("p1", BANGALORE, new PersonDetails("Asha Rao", 68, Sex.FEMALE), null);

        assertTrue(p.details().isPresent());
        assertTrue(p.emergencyContact().isEmpty());
    }
}

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
}
package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PersonDetailsTest {

    @Test
    void keepsWhatItWasGiven() {
        PersonDetails d = new PersonDetails("Asha Rao", 68, Sex.FEMALE);

        assertEquals("Asha Rao", d.name());
        assertEquals(68, d.age());
        assertEquals(Sex.FEMALE, d.sex());
    }

    /** A newborn (0) and the oldest accepted age (120) are both fine. */
    @Test
    void theEdgesOfTheAgeRangeAreAccepted() {
        assertEquals(0, new PersonDetails("Baby Rao", 0, Sex.MALE).age());
        assertEquals(PersonDetails.MAX_AGE, new PersonDetails("Old Rao", 120, Sex.OTHER).age());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PersonDetails(" ", 68, Sex.FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new PersonDetails(null, 68, Sex.FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new PersonDetails("Asha", -1, Sex.FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new PersonDetails("Asha", 121, Sex.FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new PersonDetails("Asha", 68, null));
    }
}

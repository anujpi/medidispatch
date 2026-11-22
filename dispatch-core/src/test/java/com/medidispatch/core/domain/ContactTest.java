package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContactTest {

    @Test
    void keepsWhatItWasGiven() {
        Contact c = new Contact("Ravi Kumar", "+91 98450 12345");

        assertEquals("Ravi Kumar", c.name());
        assertEquals("+91 98450 12345", c.phone());
    }

    /** Common ways of writing a number all pass: with or without +, spaces or dashes. */
    @Test
    void usualPhoneFormatsAreAccepted() {
        Contact.checkPhone("+919845012345");
        Contact.checkPhone("098450-12345");
        Contact.checkPhone("080 2345 6789");
        Contact.checkPhone("1234567");
    }

    @Test
    void aBlankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Contact(" ", "+91 98450 12345"));
        assertThrows(IllegalArgumentException.class, () -> new Contact(null, "+91 98450 12345"));
    }

    @Test
    void somethingThatIsNotAPhoneNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", null));
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", " "));
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", "call me"));
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", "123456"));             // 6 digits
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", "1234567890123456"));   // 16
        assertThrows(IllegalArgumentException.class, () -> new Contact("Ravi", "98450+12345"));        // + inside
    }
}

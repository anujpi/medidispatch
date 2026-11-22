package com.medidispatch.core.domain;

/**
 * Who a patient is: what the control room and the hospital see next to the case ("Asha Rao, 68, F").
 *
 * @param name the patient's name; never blank
 * @param age  in years, 0 to 120
 * @param sex  as recorded
 */
public record PersonDetails(String name, int age, Sex sex) {

    /** The oldest age accepted; anything above is a typing mistake. */
    public static final int MAX_AGE = 120;

    /**
     * @throws IllegalArgumentException if the name is blank, the age is out of range or the sex is missing
     */
    public PersonDetails {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (age < 0 || age > MAX_AGE) {
            throw new IllegalArgumentException("age must be 0 to " + MAX_AGE + ", got " + age);
        }
        if (sex == null) {
            throw new IllegalArgumentException("sex is required");
        }
    }
}

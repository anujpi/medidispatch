package com.medidispatch.core.domain;

/**
 * A person to call: an ambulance driver, or a patient's emergency contact.
 *
 * @param name  who it is; never blank
 * @param phone how to reach them; see {@link #checkPhone(String)}
 */
public record Contact(String name, String phone) {

    /**
     * @throws IllegalArgumentException if the name is blank or the phone number is not valid
     */
    public Contact {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        checkPhone(phone);
    }

    /**
     * A light check of a phone number: an optional leading {@code +}, then 7 to 15 digits, with spaces and dashes
     * allowed between them ({@code +91 98450-12345} passes). Not a full numbering-plan check.
     *
     * @param phone the number
     * @throws IllegalArgumentException if it is missing or does not look like a phone number
     */
    public static void checkPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        String rest = phone.strip();
        if (rest.startsWith("+")) {
            rest = rest.substring(1);
        }
        int digits = 0;
        for (char ch : rest.toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                digits++;
            } else if (ch != ' ' && ch != '-') {
                throw new IllegalArgumentException("phone can only hold digits, spaces, dashes and a leading +: " + phone);
            }
        }
        if (digits < 7 || digits > 15) {
            throw new IllegalArgumentException("phone must have 7 to 15 digits: " + phone);
        }
    }
}

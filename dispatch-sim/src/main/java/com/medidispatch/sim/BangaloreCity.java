package com.medidispatch.sim;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.PersonDetails;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.Sex;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A synthetic city laid over real Bangalore: hospitals and clinics at real neighbourhoods (with MADE-UP names and
 * phone numbers), units based at them with generated drivers, and generated patients living inside the city's edges.
 * <p>
 * Everything random comes from the config's seed, so the same seed gives the same city. No real person, hospital or
 * phone number is meant; any match is a coincidence.
 */
final class BangaloreCity {

    /** Southern edge (Electronic City). */
    static final double MIN_LATITUDE = 12.83;
    /** Northern edge (beyond Hebbal). */
    static final double MAX_LATITUDE = 13.10;
    /** Western edge (beyond Yeshwanthpur). */
    static final double MIN_LONGITUDE = 77.48;
    /** Eastern edge (Whitefield). */
    static final double MAX_LONGITUDE = 77.78;

    /** Six hospitals and four clinics at real neighbourhoods, with made-up names. */
    static final List<Facility> FACILITIES = List.of(
            hospital("blr-h-koramangala", "Koramangala General Hospital", 12.9352, 77.6245,
                    Set.of(Capability.TRAUMA, Capability.CARDIAC), "+91 80 4100 1001"),
            hospital("blr-h-jayanagar", "Jayanagar Heart & Stroke Centre", 12.9250, 77.5938,
                    Set.of(Capability.CARDIAC, Capability.STROKE), "+91 80 4100 1002"),
            hospital("blr-h-whitefield", "Whitefield City Hospital", 12.9698, 77.7500,
                    Set.of(Capability.TRAUMA), "+91 80 4100 1003"),
            hospital("blr-h-hebbal", "Hebbal Medical Centre", 13.0358, 77.5970,
                    Set.of(Capability.STROKE, Capability.PAEDIATRIC), "+91 80 4100 1004"),
            hospital("blr-h-malleshwaram", "Malleshwaram Children's Hospital", 13.0031, 77.5643,
                    Set.of(Capability.PAEDIATRIC), "+91 80 4100 1005"),
            hospital("blr-h-ecity", "Electronic City Hospital", 12.8452, 77.6602,
                    Set.of(), "+91 80 4100 1006"),
            clinic("blr-c-indiranagar", "Indiranagar Clinic", 12.9784, 77.6408, "+91 80 4200 2001"),
            clinic("blr-c-yeshwanthpur", "Yeshwanthpur Clinic", 13.0280, 77.5409, "+91 80 4200 2002"),
            clinic("blr-c-banashankari", "Banashankari Clinic", 12.9255, 77.5468, "+91 80 4200 2003"),
            clinic("blr-c-marathahalli", "Marathahalli Clinic", 12.9591, 77.6974, "+91 80 4200 2004"));

    private static final String[] WOMEN = {"Asha", "Lakshmi", "Kavya", "Divya", "Meera", "Ananya", "Shwetha", "Pooja",
            "Sunitha", "Rekha", "Nandini", "Bhavana"};
    private static final String[] MEN = {"Ravi", "Suresh", "Manjunath", "Prakash", "Arjun", "Kiran", "Naveen", "Rahul",
            "Vinay", "Ganesh", "Mahesh", "Santosh"};
    private static final String[] SURNAMES = {"Rao", "Gowda", "Reddy", "Shetty", "Kumar", "Nair", "Iyer", "Hegde",
            "Patil", "Murthy", "Sharma", "Naidu"};

    private BangaloreCity() {}

    /**
     * Everything to register, in the order it must be registered (units need their base first).
     *
     * @param facilities the hospitals and clinics
     * @param units      the units, each based at a facility and standing at its base, with a driver
     * @param patients   the patients, with their details and (for most) an emergency contact
     */
    record Plan(List<Facility> facilities, List<Resource> units, List<Patient> patients) {}

    /**
     * Lays out the city for a config: its patient and unit counts and its seed. Ambulances are spread over the
     * hospitals, nurses and doctors over the clinics, one after another.
     *
     * @param config the counts and the seed
     * @return what to register
     */
    static Plan plan(LiveConfig config) {
        Random dice = new Random(config.seed());
        List<Facility> hospitals = new ArrayList<>();
        List<Facility> clinics = new ArrayList<>();
        for (Facility f : FACILITIES) {
            if (f.isHospital()) {
                hospitals.add(f);
            } else {
                clinics.add(f);
            }
        }
        List<Resource> units = new ArrayList<>();
        addUnits(units, "als", ResourceType.AMBULANCE_ALS, config.alsCount(), hospitals, dice);
        addUnits(units, "bls", ResourceType.AMBULANCE_BLS, config.blsCount(), hospitals, dice);
        addUnits(units, "nurse", ResourceType.NURSE, config.nurseCount(), clinics, dice);
        addUnits(units, "doctor", ResourceType.DOCTOR, config.doctorCount(), clinics, dice);

        List<Patient> patients = new ArrayList<>();
        for (int i = 1; i <= config.patientCount(); i++) {
            Contact emergency = dice.nextDouble() < 0.7 ? randomContact(dice) : null;
            patients.add(new Patient(String.format("p%03d", i), randomPoint(dice), randomDetails(dice), emergency));
        }
        return new Plan(FACILITIES, units, patients);
    }

    /**
     * @param dice the randomness to use
     * @return a point anywhere inside the city's edges
     */
    static Location randomPoint(Random dice) {
        double lat = MIN_LATITUDE + dice.nextDouble() * (MAX_LATITUDE - MIN_LATITUDE);
        double lon = MIN_LONGITUDE + dice.nextDouble() * (MAX_LONGITUDE - MIN_LONGITUDE);
        return new Location(lat, lon);
    }

    /**
     * @param where a point
     * @return {@code true} if it is inside the city's edges
     */
    static boolean inside(Location where) {
        return where.latitude() >= MIN_LATITUDE && where.latitude() <= MAX_LATITUDE
                && where.longitude() >= MIN_LONGITUDE && where.longitude() <= MAX_LONGITUDE;
    }

    // one unit after another goes to the next base in the list, so the fleet is spread evenly
    private static void addUnits(List<Resource> units, String prefix, ResourceType type, int count,
                                 List<Facility> bases, Random dice) {
        for (int i = 1; i <= count; i++) {
            Facility base = bases.get((i - 1) % bases.size());
            units.add(new Resource(prefix + "-" + i, type, base.location(), base.id(), randomContact(dice)));
        }
    }

    // mostly adults of 30 to 85, about one in ten a child
    private static PersonDetails randomDetails(Random dice) {
        Sex sex = dice.nextDouble() < 0.02 ? Sex.OTHER : (dice.nextBoolean() ? Sex.FEMALE : Sex.MALE);
        int age = dice.nextDouble() < 0.1 ? dice.nextInt(18) : 30 + dice.nextInt(56);
        String first = sex == Sex.MALE ? pick(MEN, dice) : pick(WOMEN, dice);
        return new PersonDetails(first + " " + pick(SURNAMES, dice), age, sex);
    }

    private static Contact randomContact(Random dice) {
        String first = dice.nextBoolean() ? pick(WOMEN, dice) : pick(MEN, dice);
        return new Contact(first + " " + pick(SURNAMES, dice), randomMobile(dice));
    }

    // a made-up mobile number in the Indian format: +91, then 10 digits starting with 9
    private static String randomMobile(Random dice) {
        return String.format("+91 9%04d %05d", dice.nextInt(10_000), dice.nextInt(100_000));
    }

    private static String pick(String[] names, Random dice) {
        return names[dice.nextInt(names.length)];
    }

    private static Facility hospital(String id, String name, double lat, double lon, Set<Capability> capabilities,
                                     String phone) {
        return Facility.hospital(id, name, new Location(lat, lon), capabilities, true).withPhone(phone);
    }

    private static Facility clinic(String id, String name, double lat, double lon, String phone) {
        return Facility.clinic(id, name, new Location(lat, lon)).withPhone(phone);
    }
}

package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.memory.InMemoryFacilityRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each of the four destination rules on its own, plus ties and edge cases. The patient is in central Delhi; every
 * hospital is placed a known number of km due north ({@link #kmNorth}).
 */
public class DestinationChooserTest {

    private static final Location PATIENT = new Location(28.6139, 77.2090);
    private static final Set<Capability> STROKE = Set.of(Capability.STROKE);
    private static final Set<Capability> NOTHING_SPECIAL = Set.of();

    private final InMemoryFacilityRepository registry = new InMemoryFacilityRepository();
    private final DestinationChooser chooser = new DestinationChooser(registry);

    // ---- rule 1: capable and accepting, nearest first ----

    @Test
    void theNearestCapableAcceptingHospitalWins() {
        add("far-stroke", 9, STROKE, true);
        add("near-stroke", 4, STROKE, true);

        assertEquals("near-stroke", chosen(STROKE));
    }

    /** Mrs. Sharma: the nearest hospital has no stroke unit, so the farther one that does is chosen. */
    @Test
    void aFartherCapableHospitalBeatsANearerOneThatCannotTreat() {
        add("near-general", 3, NOTHING_SPECIAL, true);
        add("far-stroke", 9, STROKE, true);

        assertEquals("far-stroke", chosen(STROKE));
    }

    @Test
    void aCapableHospitalOnDiversionIsSkippedWhileAnotherCapableOneAccepts() {
        add("near-stroke-full", 5, STROKE, false);
        add("far-stroke-open", 9, STROKE, true);

        assertEquals("far-stroke-open", chosen(STROKE));
    }

    // ---- rule 2: capable, even on diversion ----

    /** Diversion is about general load: a stroke patient still goes to a stroke unit rather than one without. */
    @Test
    void whenEveryCapableHospitalIsOnDiversionTheNearestCapableOneIsStillChosen() {
        add("near-general-open", 2, NOTHING_SPECIAL, true);
        add("far-stroke-full", 12, STROKE, false);
        add("farther-stroke-full", 15, STROKE, false);

        assertEquals("far-stroke-full", chosen(STROKE));
    }

    // ---- rule 3: nobody can treat: the nearest accepting emergency department ----

    @Test
    void whenNoHospitalHasWhatIsNeededTheNearestAcceptingOneIsChosen() {
        add("near-full", 2, Set.of(Capability.CARDIAC), false);
        add("mid-open", 5, Set.of(Capability.CARDIAC), true);
        add("far-open", 9, NOTHING_SPECIAL, true);

        assertEquals("mid-open", chosen(STROKE));
    }

    // ---- rule 4: nobody can treat and nobody accepts: the nearest at all ----

    @Test
    void whenNothingFitsAndNothingAcceptsTheNearestHospitalIsChosen() {
        add("far-full", 9, NOTHING_SPECIAL, false);
        add("near-full", 3, NOTHING_SPECIAL, false);

        assertEquals("near-full", chosen(STROKE));
    }

    // ---- more than one need, nothing special, ties, edges ----

    /** An injured child needs trauma AND paediatrics in the same hospital. */
    @Test
    void whenTwoThingsAreNeededOnlyAHospitalWithBothQualifies() {
        add("near-trauma", 2, Set.of(Capability.TRAUMA), true);
        add("mid-paediatric", 4, Set.of(Capability.PAEDIATRIC), true);
        add("far-both", 10, Set.of(Capability.TRAUMA, Capability.PAEDIATRIC), true);

        assertEquals("far-both", chosen(Set.of(Capability.TRAUMA, Capability.PAEDIATRIC)));
    }

    @Test
    void nothingSpecialNeededMeansTheNearestAcceptingHospital() {
        add("near-full", 1, Set.of(Capability.STROKE), false);
        add("mid-open", 4, NOTHING_SPECIAL, true);
        add("far-open", 8, Set.of(Capability.CARDIAC), true);

        assertEquals("mid-open", chosen(NOTHING_SPECIAL));
    }

    /** Two hospitals at exactly the same distance: the lower id wins, so the answer never depends on order. */
    @Test
    void equalDistancesAreDecidedById() {
        add("h-b", 5, STROKE, true);
        add("h-a", 5, STROKE, true);

        assertEquals("h-a", chosen(STROKE));
    }

    @Test
    void anEmptyRegistryChoosesNothing() {
        assertEquals(Optional.empty(), chooser.choose(PATIENT, STROKE));
    }

    // ---- clinics are never a destination ----

    /** The clinic is nearer than every hospital, and rule 4 ("nearest at all") still skips it. */
    @Test
    void aNearerClinicIsNeverChosen() {
        registry.save(Facility.clinic("near-clinic", "Lane Clinic", kmNorth(1)));
        add("far-full", 9, NOTHING_SPECIAL, false);

        assertEquals("far-full", chosen(STROKE));
        assertEquals("far-full", chosen(NOTHING_SPECIAL));
    }

    @Test
    void aRegistryWithOnlyClinicsChoosesNothing() {
        registry.save(Facility.clinic("c1", "Lane Clinic", kmNorth(1)));
        registry.save(Facility.clinic("c2", "Market Clinic", kmNorth(2)));

        assertEquals(Optional.empty(), chooser.choose(PATIENT, NOTHING_SPECIAL));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> chooser.choose(null, STROKE));
        assertThrows(IllegalArgumentException.class, () -> chooser.choose(PATIENT, null));
    }

    // ---- helpers ----

    private void add(String id, double kmNorth, Set<Capability> capabilities, boolean accepting) {
        registry.save(Facility.hospital(id, "Hospital " + id, kmNorth(kmNorth), capabilities, accepting));
    }

    private String chosen(Set<Capability> needed) {
        return chooser.choose(PATIENT, needed).orElseThrow().id();
    }

    /** 0.009 degrees of latitude is about 1 km, so this is a point roughly {@code km} north of the patient. */
    private static Location kmNorth(double km) {
        return new Location(PATIENT.latitude() + km * 0.009, PATIENT.longitude());
    }

    // ---- travel time (R4) ----

    /** Two stroke units: the nearer one is stuck behind traffic, the farther one is quicker by road. */
    @Test
    void aFartherCapableHospitalThatIsQuickerByRoadWins() {
        add("near-stroke", 3, STROKE, true);
        add("far-stroke", 9, STROKE, true);
        FixedTravelTime roads = new FixedTravelTime().to(kmNorth(3), 1_500).to(kmNorth(9), 700);

        assertEquals("far-stroke", new DestinationChooser(registry, roads).choose(PATIENT, STROKE).orElseThrow().id());
    }

    /** The only stroke unit cannot be reached (a closure): it is skipped and the rules go on to an open hospital. */
    @Test
    void aHospitalThatCannotBeReachedIsSkipped() {
        add("stroke-cut-off", 4, STROKE, true);
        add("general", 6, NOTHING_SPECIAL, true);
        FixedTravelTime roads = new FixedTravelTime().unreachableTo(kmNorth(4));

        assertEquals("general", new DestinationChooser(registry, roads).choose(PATIENT, STROKE).orElseThrow().id());
    }

    @Test
    void missingArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DestinationChooser(null, new StraightLineTravelTime()));
        assertThrows(IllegalArgumentException.class, () -> new DestinationChooser(registry, null));
    }
}

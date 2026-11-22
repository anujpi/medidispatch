package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;
import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcFacilityRepository} on a real Postgres + PostGIS (the shared {@link TestDatabase}); mirrors
 * InMemoryFacilityRepositoryTest.
 */
class JdbcFacilityRepositoryTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);
    private static final Location NOIDA = new Location(28.5355, 77.3910);

    private JdbcFacilityRepository repo;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset();
        repo = new JdbcFacilityRepository(TestDatabase.jdbc());
    }

    /** Every field survives the trip through Postgres, including the location (not swapped) and the array. */
    @Test
    void aSavedHospitalComesBackTheSame() {
        Facility apollo = Facility.hospital("h-apollo", "Apollo", DELHI,
                Set.of(Capability.STROKE, Capability.CARDIAC), true);

        repo.save(apollo);

        assertEquals(apollo, repo.findById("h-apollo").orElseThrow());
    }

    /** An empty set (emergency department only) is stored as an empty array, not NULL, and comes back empty. */
    @Test
    void noCapabilitiesComeBackAsAnEmptySet() {
        repo.save(Facility.hospital("h-general", "City General", DELHI, Set.of(), false));

        assertEquals(Set.of(), repo.findById("h-general").orElseThrow().capabilities());
    }

    @Test
    void aClinicComesBackAsAClinic() {
        Facility clinic = Facility.clinic("c-lane", "Lane Clinic", NOIDA);

        repo.save(clinic);

        Facility found = repo.findById("c-lane").orElseThrow();
        assertEquals(clinic, found);
        assertEquals(FacilityKind.CLINIC, found.kind());
    }

    @Test
    void unknownIdIsEmpty() {
        assertEquals(Optional.empty(), repo.findById("nowhere"));
    }

    /** save is an upsert: saving again replaces the stored row. This is how diversion is switched on and off. */
    @Test
    void savingAgainReplacesTheFacility() {
        Facility open = Facility.hospital("h1", "Apollo", DELHI, Set.of(Capability.TRAUMA), true);
        repo.save(open);

        repo.save(open.withAccepting(false));

        assertFalse(repo.findById("h1").orElseThrow().accepting());
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void findAllIsSortedByIdAndHoldsHospitalsAndClinics() {
        repo.save(Facility.hospital("h3", "Three", DELHI, Set.of(), true));
        repo.save(Facility.clinic("c1", "One", NOIDA));
        repo.save(Facility.hospital("h2", "Two", DELHI, Set.of(Capability.PAEDIATRIC), true));

        List<String> ids = new ArrayList<>();
        for (Facility f : repo.findAll()) {
            ids.add(f.id());
        }
        assertEquals(List.of("c1", "h2", "h3"), ids);
    }

    @Test
    void findAllOnAnEmptyTableIsEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
    }
}

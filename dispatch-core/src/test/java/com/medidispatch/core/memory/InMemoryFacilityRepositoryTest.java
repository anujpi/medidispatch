package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;
import com.medidispatch.core.domain.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryFacilityRepositoryTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);

    private final InMemoryFacilityRepository repo = new InMemoryFacilityRepository();

    private static Facility hospital(String id) {
        return Facility.hospital(id, "Hospital " + id, DELHI, Set.of(Capability.CARDIAC), true);
    }

    @Test
    void savedFacilityCanBeFoundById() {
        repo.save(hospital("h1"));

        assertEquals("Hospital h1", repo.findById("h1").orElseThrow().name());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    /** Saving again replaces: this is how diversion is switched on and off. */
    @Test
    void savingAgainReplacesTheFacility() {
        Facility open = hospital("h1");
        repo.save(open);

        repo.save(open.withAccepting(false));

        assertFalse(repo.findById("h1").orElseThrow().accepting());
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void findAllIsSortedById() {
        repo.save(hospital("h3"));
        repo.save(hospital("h1"));
        repo.save(hospital("h2"));

        List<String> ids = new ArrayList<>();
        for (Facility f : repo.findAll()) {
            ids.add(f.id());
        }
        assertEquals(List.of("h1", "h2", "h3"), ids);
    }

    @Test
    void hospitalsAndClinicsAreKeptSideBySide() {
        repo.save(hospital("h1"));
        repo.save(Facility.clinic("c1", "Lane Clinic", DELHI));

        assertEquals(2, repo.findAll().size());
        assertEquals(FacilityKind.CLINIC, repo.findById("c1").orElseThrow().kind());
        assertEquals(FacilityKind.HOSPITAL, repo.findById("h1").orElseThrow().kind());
    }

    @Test
    void findAllOnAnEmptyRegistryIsEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
    }
}

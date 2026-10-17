package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryPatientRepositoryTest {

    private static final Location BANGALORE = new Location(12.9716, 77.5946);
    private static final Location CHENNAI = new Location(13.0827, 80.2707);

    private final InMemoryPatientRepository repo = new InMemoryPatientRepository();

    @Test
    void savedPatientCanBeFoundById() {
        repo.save(new Patient("p1", BANGALORE));

        Patient found = repo.findById("p1").get();

        assertEquals("p1", found.id());
        assertEquals(BANGALORE, found.location());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameIdTwiceReplacesTheFirst() {
        repo.save(new Patient("p1", BANGALORE));
        repo.save(new Patient("p1", CHENNAI));

        assertEquals(CHENNAI, repo.findById("p1").get().location());
    }

    @Test
    void updatedLocationIsSeenAfterSavingAgain() {
        Patient p = new Patient("p1", BANGALORE);
        repo.save(p);

        p.updateLocation(CHENNAI);
        repo.save(p);                                   // always save after changing

        assertEquals(CHENNAI, repo.findById("p1").get().location());
    }

    @Test
    void differentPatientsAreStoredIndependently() {
        repo.save(new Patient("p1", BANGALORE));
        repo.save(new Patient("p2", CHENNAI));

        assertEquals(BANGALORE, repo.findById("p1").get().location());
        assertEquals(CHENNAI, repo.findById("p2").get().location());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
    }
}
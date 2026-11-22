package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcPatientRepository} on a real Postgres + PostGIS (the shared {@link TestDatabase}); mirrors
 * InMemoryPatientRepositoryTest.
 */
class JdbcPatientRepositoryTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);
    private static final Location MUMBAI = new Location(19.0760, 72.8777);

    private JdbcPatientRepository repo;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset();
        repo = new JdbcPatientRepository(TestDatabase.jdbc());
    }

    /** The home position survives the trip through PostGIS exactly (latitude and longitude not swapped). */
    @Test
    void savedPatientCanBeFoundById() {
        repo.save(new Patient("p1", DELHI));

        Patient found = repo.findById("p1").orElseThrow();
        assertEquals("p1", found.id());
        assertEquals(DELHI, found.location());
    }

    @Test
    void unknownIdIsEmpty() {
        assertEquals(Optional.empty(), repo.findById("nobody"));
    }

    /** save is an upsert: the same id again replaces the stored home. */
    @Test
    void savingTheSameIdTwiceReplacesTheFirst() {
        repo.save(new Patient("p1", DELHI));
        repo.save(new Patient("p1", MUMBAI));

        assertEquals(MUMBAI, repo.findById("p1").orElseThrow().location());
    }

    @Test
    void differentPatientsAreStoredIndependently() {
        repo.save(new Patient("p1", DELHI));
        repo.save(new Patient("p2", MUMBAI));

        assertEquals(DELHI, repo.findById("p1").orElseThrow().location());
        assertEquals(MUMBAI, repo.findById("p2").orElseThrow().location());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
    }
}

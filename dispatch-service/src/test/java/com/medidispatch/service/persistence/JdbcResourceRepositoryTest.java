package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Contact;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcResourceRepository} on a real Postgres + PostGIS (the shared {@link TestDatabase}); mirrors
 * InMemoryResourceRepositoryTest, and checks that both adapters give the same answers for the same fleet.
 */
class JdbcResourceRepositoryTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
    private static final Set<ResourceType> AMBULANCES = Set.of(ResourceType.AMBULANCE_ALS, ResourceType.AMBULANCE_BLS);
    private static final Set<ResourceType> ALS = Set.of(ResourceType.AMBULANCE_ALS);

    private JdbcResourceRepository repo;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset();
        repo = new JdbcResourceRepository(TestDatabase.jdbc());
    }

    // ---- storing and reading back ----

    /** Every field survives the round trip: status, both positions (not swapped), both times. */
    @Test
    void savedUnitComesBackWithEveryField() {
        repo.save(Resource.restore("u1", ResourceType.DOCTOR, null, null, ResourceStatus.ASSIGNED, kmNorth(1), DELHI,
                T0, T0.minusSeconds(60)));

        Resource found = repo.findById("u1").orElseThrow();
        assertEquals("u1", found.id());
        assertEquals(ResourceType.DOCTOR, found.type());
        assertEquals(ResourceStatus.ASSIGNED, found.status());
        assertEquals(kmNorth(1), found.location());
        assertEquals(DELHI, found.movedFrom());
        assertEquals(Optional.of(T0), found.lastSeenAt());
        assertEquals(Optional.of(T0.minusSeconds(60)), found.lastMovedAt());
    }

    /** The base (V2: units.base_id) survives the round trip; no base comes back as empty. */
    @Test
    void theBaseComesBack() {
        new JdbcFacilityRepository(TestDatabase.jdbc()).save(Facility.clinic("c-lane", "Lane Clinic", DELHI));

        repo.save(new Resource("u-based", ResourceType.NURSE, DELHI, "c-lane"));
        repo.save(new Resource("u-free", ResourceType.NURSE, DELHI));

        assertEquals(Optional.of("c-lane"), repo.findById("u-based").orElseThrow().baseId());
        assertEquals(Optional.empty(), repo.findById("u-free").orElseThrow().baseId());
    }

    /** The driver (V3) survives the round trip; no driver comes back as empty. */
    @Test
    void theDriverComesBack() {
        Contact ravi = new Contact("Ravi Kumar", "+91 98450 12345");

        repo.save(new Resource("u-driven", ResourceType.AMBULANCE_ALS, DELHI, null, ravi));
        repo.save(new Resource("u-nobody", ResourceType.AMBULANCE_ALS, DELHI));

        assertEquals(Optional.of(ravi), repo.findById("u-driven").orElseThrow().driver());
        assertEquals(Optional.empty(), repo.findById("u-nobody").orElseThrow().driver());
    }

    /** NULL in the table is "never" in the domain. */
    @Test
    void aUnitThatNeverReportedComesBackWithEmptyTimes() {
        repo.save(new Resource("u1", ResourceType.NURSE, DELHI));

        Resource found = repo.findById("u1").orElseThrow();
        assertTrue(found.lastSeenAt().isEmpty());
        assertTrue(found.lastMovedAt().isEmpty());
    }

    @Test
    void unknownIdIsEmpty() {
        assertEquals(Optional.empty(), repo.findById("nobody"));
    }

    /** The adapter sees only what was saved: change a unit, save it again, read the new state. */
    @Test
    void savingAgainUpdatesTheUnit() {
        Resource unit = new Resource("u1", ResourceType.NURSE, DELHI);
        repo.save(unit);

        unit.transitionTo(ResourceStatus.ASSIGNED);
        unit.reportLocation(kmNorth(2), T0);
        repo.save(unit);

        Resource found = repo.findById("u1").orElseThrow();
        assertEquals(ResourceStatus.ASSIGNED, found.status());
        assertEquals(kmNorth(2), found.location());
        assertEquals(kmNorth(2), found.movedFrom());
        assertEquals(Optional.of(T0), found.lastSeenAt());
        assertEquals(Optional.of(T0), found.lastMovedAt());
    }

    /** Column ownership: saving a unit must not undo its claim (claimed_by belongs to the claimer). */
    @Test
    void savingAUnitLeavesItsClaimAlone() {
        Resource unit = new Resource("u1", ResourceType.NURSE, DELHI);
        repo.save(unit);
        TestDatabase.openCase("c1");
        JdbcResourceClaimer claimer = new JdbcResourceClaimer(TestDatabase.jdbc());
        claimer.tryClaim("u1", "c1");

        unit.transitionTo(ResourceStatus.ASSIGNED);
        repo.save(unit);

        assertEquals(Optional.of("c1"), claimer.claimedBy("u1"));
    }

    // ---- the nearby searches ----

    @Test
    void includesUnitsInsideTheRadiusAndExcludesOnesOutside() {
        repo.save(new Resource("near", ResourceType.AMBULANCE_ALS, kmNorth(2)));
        repo.save(new Resource("far", ResourceType.AMBULANCE_ALS, kmNorth(30)));

        assertEquals(List.of("near"), ids(repo.findAvailableNear(DELHI, 20, ALS)));
    }

    @Test
    void excludesOtherStatusesAndTypes() {
        repo.save(new Resource("free-als", ResourceType.AMBULANCE_ALS, kmNorth(1)));
        repo.save(withStatus("busy-als", ResourceType.AMBULANCE_ALS, ResourceStatus.ASSIGNED, kmNorth(1)));
        repo.save(withStatus("off-als", ResourceType.AMBULANCE_ALS, ResourceStatus.OFFLINE, kmNorth(1)));
        repo.save(new Resource("free-nurse", ResourceType.NURSE, kmNorth(1)));

        assertEquals(List.of("free-als"), ids(repo.findAvailableNear(DELHI, 20, ALS)));
    }

    @Test
    void severalTypesCanBeRequestedAtOnce() {
        repo.save(new Resource("als", ResourceType.AMBULANCE_ALS, kmNorth(1)));
        repo.save(new Resource("bls", ResourceType.AMBULANCE_BLS, kmNorth(2)));
        repo.save(new Resource("nurse", ResourceType.NURSE, kmNorth(3)));

        assertEquals(List.of("als", "bls"), ids(repo.findAvailableNear(DELHI, 20, AMBULANCES)));
    }

    /** An empty set returns early: the SQL "IN ()" would be invalid. */
    @Test
    void emptyTypeSetReturnsNothing() {
        repo.save(new Resource("als", ResourceType.AMBULANCE_ALS, kmNorth(1)));

        assertEquals(List.of(), repo.findAvailableNear(DELHI, 20, Set.of()));
    }

    /** Nearest first; two units at the same spot come out by id. */
    @Test
    void resultsAreNearestFirstThenById() {
        repo.save(new Resource("u-c", ResourceType.AMBULANCE_ALS, kmNorth(3)));
        repo.save(new Resource("u-b", ResourceType.AMBULANCE_ALS, kmNorth(3)));
        repo.save(new Resource("u-a", ResourceType.AMBULANCE_ALS, kmNorth(1)));

        assertEquals(List.of("u-a", "u-b", "u-c"), ids(repo.findAvailableNear(DELHI, 20, ALS)));
    }

    @Test
    void assignedSearchReturnsOnlyAssignedUnitsNearestFirst() {
        repo.save(withStatus("busy-far", ResourceType.AMBULANCE_ALS, ResourceStatus.ASSIGNED, kmNorth(5)));
        repo.save(withStatus("busy-near", ResourceType.AMBULANCE_ALS, ResourceStatus.ASSIGNED, kmNorth(1)));
        repo.save(withStatus("busy-outside", ResourceType.AMBULANCE_ALS, ResourceStatus.ASSIGNED, kmNorth(30)));
        repo.save(new Resource("free", ResourceType.AMBULANCE_ALS, kmNorth(1)));

        assertEquals(List.of("busy-near", "busy-far"), ids(repo.findAssignedNear(DELHI, 20, ALS)));
    }

    @Test
    void findAllReturnsEveryUnitWhateverItsStatusSortedById() {
        repo.save(withStatus("u3", ResourceType.NURSE, ResourceStatus.OFFLINE, kmNorth(50)));
        repo.save(new Resource("u1", ResourceType.AMBULANCE_ALS, DELHI));
        repo.save(withStatus("u2", ResourceType.DOCTOR, ResourceStatus.ASSIGNED, kmNorth(1)));

        assertEquals(List.of("u1", "u2", "u3"), ids(repo.findAll()));
    }

    @Test
    void findAllOnAnEmptyDatabaseIsEmpty() {
        assertEquals(List.of(), repo.findAll());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(null, 20, ALS));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(DELHI, 20, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(DELHI, 0, ALS));
        assertThrows(IllegalArgumentException.class, () -> repo.findAssignedNear(DELHI, -1, ALS));
    }

    /**
     * Both adapters must give the same answers: 60 units of random types and statuses scattered up to about 35 km
     * around Delhi (fixed seed, so the same every run) go into both, then both answer the same searches from
     * several centers. Same order too, because both use sphere distances, then id.
     */
    @Test
    void givesTheSameAnswersAsTheInMemoryRepository() {
        InMemoryResourceRepository memory = new InMemoryResourceRepository();
        Random random = new Random(42);
        ResourceType[] types = ResourceType.values();
        ResourceStatus[] statuses = ResourceStatus.values();
        for (int i = 0; i < 60; i++) {
            Location where = new Location(DELHI.latitude() + (random.nextDouble() - 0.5) * 0.6,
                    DELHI.longitude() + (random.nextDouble() - 0.5) * 0.6);
            String id = "u" + i;
            ResourceType type = types[random.nextInt(types.length)];
            ResourceStatus status = statuses[random.nextInt(statuses.length)];
            repo.save(withStatus(id, type, status, where));
            memory.save(withStatus(id, type, status, where));
        }

        List<Location> centers = List.of(DELHI, kmNorth(10), new Location(28.50, 77.10), new Location(28.75, 77.35));
        for (Location center : centers) {
            assertEquals(ids(memory.findAvailableNear(center, 20, AMBULANCES)), ids(repo.findAvailableNear(center, 20, AMBULANCES)));
            assertEquals(ids(memory.findAssignedNear(center, 20, AMBULANCES)), ids(repo.findAssignedNear(center, 20, AMBULANCES)));
            assertEquals(ids(memory.findAvailableNear(center, 8, Set.of(ResourceType.NURSE, ResourceType.DOCTOR))),
                    ids(repo.findAvailableNear(center, 8, Set.of(ResourceType.NURSE, ResourceType.DOCTOR))));
        }
    }

    // ---- helpers ----

    /** 0.009 degrees of latitude is about 1 km, so this is a point roughly {@code km} north of Delhi. */
    private static Location kmNorth(double km) {
        return new Location(DELHI.latitude() + km * 0.009, DELHI.longitude());
    }

    /** A unit at {@code where} in the given status, never seen. */
    private static Resource withStatus(String id, ResourceType type, ResourceStatus status, Location where) {
        return Resource.restore(id, type, null, null, status, where, where, null, null);
    }

    private static List<String> ids(List<Resource> units) {
        List<String> ids = new ArrayList<>();
        for (Resource r : units) {
            ids.add(r.id());
        }
        return ids;
    }
}

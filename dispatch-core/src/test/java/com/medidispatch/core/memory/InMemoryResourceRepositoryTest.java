package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryResourceRepositoryTest {

    private static final Location ORIGIN = new Location(0, 0);
    // about 1.1 km, 5.6 km and 11.1 km north of the origin
    private static final Location NEAR = new Location(0.01, 0);
    private static final Location MID = new Location(0.05, 0);
    private static final Location FAR = new Location(0.1, 0);

    private final InMemoryResourceRepository repo = new InMemoryResourceRepository();

    private Resource resource(String id, ResourceType type, Location where) {
        Resource r = new Resource(id, type, where);
        repo.save(r);
        return r;
    }

    private List<String> ids(List<Resource> list) {
        return list.stream().map(r -> r.id()).toList();
    }

    // ---------- save / findById ----------

    @Test
    void savedResourceCanBeFoundById() {
        resource("r1", ResourceType.NURSE, NEAR);

        assertEquals("r1", repo.findById("r1").get().id());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameIdTwiceReplacesTheFirst() {
        resource("r1", ResourceType.NURSE, NEAR);
        resource("r1", ResourceType.COURIER, FAR);

        assertEquals(ResourceType.COURIER, repo.findById("r1").get().type());
    }

    @Test
    void nullSaveAndNullFindAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
    }

    // ---------- findAvailableNear: filtering ----------

    @Test
    void includesResourcesInsideTheRadiusAndExcludesOnesOutside() {
        resource("near", ResourceType.NURSE, NEAR);
        resource("far", ResourceType.NURSE, FAR);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE));

        assertEquals(List.of("near"), ids(found));
    }

    @Test
    void excludesAssignedAndOfflineResources() {
        Resource assigned = resource("assigned", ResourceType.NURSE, NEAR);
        Resource offline = resource("offline", ResourceType.NURSE, NEAR);
        resource("free", ResourceType.NURSE, NEAR);
        assigned.transitionTo(ResourceStatus.ASSIGNED);
        offline.transitionTo(ResourceStatus.OFFLINE);
        repo.save(assigned);                               // always save after changing
        repo.save(offline);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE));

        assertEquals(List.of("free"), ids(found));
    }

    @Test
    void excludesTypesThatWereNotRequested() {
        resource("nurse", ResourceType.NURSE, NEAR);
        resource("courier", ResourceType.COURIER, NEAR);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE));

        assertEquals(List.of("nurse"), ids(found));
    }

    @Test
    void severalTypesCanBeRequestedAtOnce() {
        resource("nurse", ResourceType.NURSE, NEAR);
        resource("courier", ResourceType.COURIER, NEAR);
        resource("als", ResourceType.AMBULANCE_ALS, NEAR);

        List<Resource> found = repo.findAvailableNear(
                ORIGIN, 3.0, Set.of(ResourceType.NURSE, ResourceType.COURIER));

        assertEquals(Set.of("nurse", "courier"), Set.copyOf(ids(found)));
    }

    @Test
    void emptyTypeSetReturnsNothing() {
        resource("nurse", ResourceType.NURSE, NEAR);

        assertTrue(repo.findAvailableNear(ORIGIN, 3.0, Set.of()).isEmpty());
    }

    @Test
    void emptyRepositoryReturnsNothing() {
        assertTrue(repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE)).isEmpty());
    }

    // ---------- findAvailableNear: ordering ----------

    @Test
    void resultsAreNearestFirst() {
        resource("far", ResourceType.NURSE, FAR);
        resource("near", ResourceType.NURSE, NEAR);
        resource("mid", ResourceType.NURSE, MID);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 20.0, Set.of(ResourceType.NURSE));

        assertEquals(List.of("near", "mid", "far"), ids(found));
    }

    @Test
    void equalDistancesAreOrderedById() {
        resource("b", ResourceType.NURSE, NEAR);
        resource("a", ResourceType.NURSE, NEAR);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE));

        assertEquals(List.of("a", "b"), ids(found));
    }

    // ---------- result list is separate ----------

    @Test
    void changingTheReturnedListDoesNotAffectTheRepository() {
        resource("r1", ResourceType.NURSE, NEAR);

        List<Resource> found = repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE));
        found.clear();

        assertEquals(1, repo.findAvailableNear(ORIGIN, 3.0, Set.of(ResourceType.NURSE)).size());
    }

    // ---------- validation ----------

    @Test
    void invalidArgumentsAreRejected() {
        Set<ResourceType> nurse = Set.of(ResourceType.NURSE);

        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(null, 3.0, nurse));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(ORIGIN, 3.0, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(ORIGIN, 0, nurse));
        assertThrows(IllegalArgumentException.class, () -> repo.findAvailableNear(ORIGIN, -1, nurse));
    }
}
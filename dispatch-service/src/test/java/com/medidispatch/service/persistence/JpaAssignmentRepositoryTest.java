package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JpaAssignmentRepository} on a real Postgres (the shared {@link TestDatabase}); mirrors
 * InMemoryAssignmentRepositoryTest, plus what is special about JPA.
 *
 * <p>{@code @DataJpaTest} is a "slice": it starts only the JPA part of Spring (entities, Spring Data repositories,
 * DataSource, Flyway, and the {@code ddl-auto=validate} check, so this class starting at all proves TripEntity
 * matches the table). Each test runs in a transaction that is ROLLED BACK at the end, so no test leaks rows.
 * {@code @AutoConfigureTestDatabase(replace = NONE)} stops the slice from swapping our Postgres for an in-memory
 * database. The slice also prints Hibernate's SQL: look for the SELECT before each INSERT (the merge in save).
 *
 * <p>Domain objects restored from the database are new objects, so trips are compared by id, never by identity.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaAssignmentRepositoryTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);
    private static final Instant ASSIGNED_AT = Instant.parse("2026-11-22T12:00:00Z");

    @Autowired
    private TripJpaRepository trips;

    /** Spring's DataSource: a JdbcClient over it joins the test's transaction (rolled back with it). */
    @Autowired
    private DataSource dataSource;

    private JpaAssignmentRepository repo;

    /**
     * Points this slice at the {@code test} database, like the other adapter tests. {@code @DynamicPropertySource}
     * is the per-class way to give Spring values known only at runtime (TestDatabaseConfig does the same job with a
     * bean, for the whole-application tests).
     *
     * @param registry where the values go; they override application.properties
     */
    @DynamicPropertySource
    static void adapterDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestDatabase.url());
        registry.add("spring.datasource.username", () -> TestDatabase.username());
        registry.add("spring.datasource.password", () -> TestDatabase.password());
    }

    /** The plain JDBC tests leave their last rows committed; start this class from empty tables once. */
    @BeforeAll
    static void emptyTables() {
        TestDatabase.reset();
    }

    /** Trips reference cases and units (foreign keys), so every test gets cases c1-c4 and units to send. */
    @BeforeEach
    void casesAndUnits() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        for (String caseId : List.of("c1", "c2", "c3", "c4")) {
            TestDatabase.openCase(jdbc, caseId);
        }
        JdbcResourceRepository units = new JdbcResourceRepository(jdbc);
        for (String unitId : List.of("u1", "u2", "u3", "u4", "als", "nurse", "bls")) {
            units.save(new Resource(unitId, ResourceType.AMBULANCE_ALS, DELHI));
        }
        repo = new JpaAssignmentRepository(trips);
    }

    // ---- save / findById ----

    @Test
    void savedTripCanBeFoundById() {
        Assignment a = trip("c1", "u1");
        repo.save(a);

        Assignment found = repo.findById(a.id()).orElseThrow();
        assertEquals("c1", found.caseId());
        assertEquals("u1", found.resourceId());
    }

    /** Every field round-trips, including the kept id, a SUPPORT role and the exact time. */
    @Test
    void everyFieldComesBack() {
        Assignment a = Assignment.restore("t1", "c1", "nurse", AssignmentRole.SUPPORT, AssignmentStatus.ARRIVED, ASSIGNED_AT, null);
        repo.save(a);

        Assignment found = repo.findById("t1").orElseThrow();
        assertEquals("t1", found.id());
        assertEquals("c1", found.caseId());
        assertEquals("nurse", found.resourceId());
        assertEquals(AssignmentRole.SUPPORT, found.role());
        assertEquals(AssignmentStatus.ARRIVED, found.status());
        assertEquals(ASSIGNED_AT, found.assignedAt());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(repo.findById("nope").isEmpty());
    }

    @Test
    void savingTheSameTripTwiceKeepsOneRow() {
        Assignment a = trip("c1", "u1");
        repo.save(a);
        repo.save(a);

        assertEquals(1, repo.findActiveByCaseId("c1").size());
        assertEquals(1, trips.count());
    }

    @Test
    void aStatusChangeIsSeenAfterSavingAgain() {
        Assignment a = trip("c1", "u1");
        repo.save(a);

        a.transitionTo(AssignmentStatus.EN_ROUTE);
        repo.save(a);

        assertEquals(AssignmentStatus.EN_ROUTE, repo.findById(a.id()).orElseThrow().status());
    }

    @Test
    void aDemotionIsSeenAfterSavingAgain() {
        Assignment a = trip("c1", "u1");
        repo.save(a);

        a.demoteToSupport();
        repo.save(a);

        assertEquals(AssignmentRole.SUPPORT, repo.findById(a.id()).orElseThrow().role());
    }

    /**
     * saveAndFlush sends the SQL at once, so the database's one_active_trip_per_unit index refuses a second active
     * trip for u1 RIGHT HERE (with plain save, it would only fail later, at commit).
     */
    @Test
    void aSecondActiveTripForOneUnitIsRefusedAtSave() {
        repo.save(trip("c1", "u1"));

        Assignment second = trip("c2", "u1");
        assertThrows(DataIntegrityViolationException.class, () -> repo.save(second));
    }

    // ---- active lookup by case ----

    @Test
    void anActiveTripIsFoundByCaseId() {
        Assignment a = trip("c1", "u1");
        repo.save(a);

        assertEquals(a.id(), repo.findActiveByCaseId("c1").get(0).id());
    }

    @Test
    void aCaseWithNoTripHasNoActiveOne() {
        repo.save(trip("c1", "u1"));

        assertTrue(repo.findActiveByCaseId("c2").isEmpty());
    }

    @Test
    void aDoneTripIsNotActive() {
        Assignment a = trip("c1", "u1");
        finish(a);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("c1").isEmpty());
    }

    @Test
    void aCancelledTripIsNotActive() {
        Assignment a = trip("c1", "u1");
        a.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("c1").isEmpty());
    }

    @Test
    void aTimedOutTripIsNotActiveForItsCase() {
        Assignment a = trip("c1", "u1");
        a.transitionTo(AssignmentStatus.TIMED_OUT);
        repo.save(a);

        assertTrue(repo.findActiveByCaseId("c1").isEmpty());
    }

    /** What a reassignment looks like: the old trip cancelled, a new one with another unit. */
    @Test
    void theLiveTripIsFoundEvenIfAnOlderOneWasCancelled() {
        Assignment old = trip("c1", "u1");
        old.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(old);
        Assignment current = trip("c1", "u2");
        repo.save(current);

        Assignment found = repo.findActiveByCaseId("c1").get(0);
        assertEquals(current.id(), found.id());
        assertEquals("u2", found.resourceId());
    }

    @Test
    void otherCasesTripsAreNotMixedUp() {
        Assignment a = trip("c1", "u1");
        Assignment b = trip("c2", "u2");
        repo.save(a);
        repo.save(b);

        assertEquals(List.of(a.id()), ids(repo.findActiveByCaseId("c1")));
        assertEquals(List.of(b.id()), ids(repo.findActiveByCaseId("c2")));
    }

    /** An ambulance and a first responder at once; the finished one is left out. Oldest first. */
    @Test
    void aCaseCanHaveSeveralActiveTripsAtOnce() {
        Assignment ambulance = new Assignment("c1", "als", AssignmentRole.PRIMARY, ASSIGNED_AT);
        Assignment nurse = new Assignment("c1", "nurse", AssignmentRole.SUPPORT, ASSIGNED_AT.plusSeconds(30));
        Assignment finished = trip("c1", "bls");
        finish(finished);
        repo.save(ambulance);
        repo.save(nurse);
        repo.save(finished);

        assertEquals(List.of(ambulance.id(), nurse.id()), ids(repo.findActiveByCaseId("c1")));
    }

    // ---- active lookup by unit ----

    @Test
    void aUnitsActiveTripIsFoundByItsId() {
        Assignment a = trip("c1", "u1");
        repo.save(a);

        assertEquals(a.id(), repo.findActiveByResourceId("u1").orElseThrow().id());
    }

    @Test
    void aUnitWhoseTripsHaveAllEndedHasNoActiveTrip() {
        Assignment old = trip("c1", "u1");
        finish(old);
        repo.save(old);

        assertTrue(repo.findActiveByResourceId("u1").isEmpty());
        assertTrue(repo.findActiveByResourceId("never-used").isEmpty());
    }

    @Test
    void theUnitsCurrentTripIsFoundEvenIfAnOlderOneEnded() {
        Assignment old = trip("c1", "u1");
        old.transitionTo(AssignmentStatus.CANCELLED);
        repo.save(old);
        Assignment current = trip("c2", "u1");
        repo.save(current);

        assertEquals(current.id(), repo.findActiveByResourceId("u1").orElseThrow().id());
    }

    // ---- findActive ----

    @Test
    void findActiveIsEmptyWhenNothingIsStored() {
        assertTrue(repo.findActive().isEmpty());
    }

    @Test
    void findActiveReturnsOnlyTripsThatAreNotFinished() {
        Assignment live = trip("c1", "u1");
        Assignment done = trip("c2", "u2");
        Assignment cancelled = trip("c3", "u3");
        Assignment timedOut = trip("c4", "u4");
        finish(done);
        cancelled.transitionTo(AssignmentStatus.CANCELLED);
        timedOut.transitionTo(AssignmentStatus.TIMED_OUT);
        repo.save(live);
        repo.save(done);
        repo.save(cancelled);
        repo.save(timedOut);

        assertEquals(List.of(live.id()), ids(repo.findActive()));
    }

    /** New with the database: a fixed order, oldest first, then by id (in memory it was HashMap order). */
    @Test
    void findActiveIsOldestFirstThenById() {
        Assignment late = Assignment.restore("t-late", "c1", "u1", AssignmentRole.PRIMARY, AssignmentStatus.EN_ROUTE, ASSIGNED_AT.plusSeconds(60), null);
        Assignment early = Assignment.restore("t-early", "c2", "u2", AssignmentRole.PRIMARY, AssignmentStatus.ASSIGNED, ASSIGNED_AT, null);
        Assignment sameTimeB = Assignment.restore("t-b", "c3", "u3", AssignmentRole.PRIMARY, AssignmentStatus.ARRIVED, ASSIGNED_AT.plusSeconds(30), null);
        Assignment sameTimeA = Assignment.restore("t-a", "c4", "u4", AssignmentRole.PRIMARY, AssignmentStatus.ASSIGNED, ASSIGNED_AT.plusSeconds(30), null);
        repo.save(late);
        repo.save(early);
        repo.save(sameTimeB);
        repo.save(sameTimeA);

        assertEquals(List.of("t-early", "t-a", "t-b", "t-late"), ids(repo.findActive()));
    }

    @Test
    void findActiveReturnsAListTheCallerCanChange() {
        repo.save(trip("c1", "u1"));

        repo.findActive().clear();

        assertEquals(1, repo.findActive().size());
    }

    // ---- validation ----

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.save(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findActiveByCaseId(null));
        assertThrows(IllegalArgumentException.class, () -> repo.findActiveByResourceId(null));
    }

    // ---- helpers ----

    /** A new PRIMARY trip (random id, ASSIGNED). */
    private static Assignment trip(String caseId, String unitId) {
        return new Assignment(caseId, unitId, AssignmentRole.PRIMARY, ASSIGNED_AT);
    }

    /** Takes a trip through its normal ending: en route, arrived, done. */
    private static void finish(Assignment a) {
        a.transitionTo(AssignmentStatus.EN_ROUTE);
        a.transitionTo(AssignmentStatus.ARRIVED);
        a.transitionTo(AssignmentStatus.DONE);
    }

    private static List<String> ids(List<Assignment> trips) {
        List<String> ids = new ArrayList<>();
        for (Assignment a : trips) {
            ids.add(a.id());
        }
        return ids;
    }
}

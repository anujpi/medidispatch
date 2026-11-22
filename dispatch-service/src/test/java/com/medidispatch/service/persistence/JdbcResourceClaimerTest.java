package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcResourceClaimer} on a real Postgres (the shared {@link TestDatabase}); mirrors
 * InMemoryResourceClaimerTest, including the race where many claimers want one unit at once.
 *
 * <p>Every test starts with units u1 and u2 and open cases c1 and c2 ({@code claimed_by} references a case).
 */
class JdbcResourceClaimerTest {

    private static final Location DELHI = new Location(28.6139, 77.2090);

    private JdbcResourceClaimer claimer;

    @BeforeEach
    void twoUnitsAndTwoCases() {
        TestDatabase.reset();
        claimer = new JdbcResourceClaimer(TestDatabase.jdbc());
        JdbcResourceRepository units = new JdbcResourceRepository(TestDatabase.jdbc());
        units.save(new Resource("u1", ResourceType.AMBULANCE_ALS, DELHI));
        units.save(new Resource("u2", ResourceType.AMBULANCE_ALS, DELHI));
        TestDatabase.openCase("c1");
        TestDatabase.openCase("c2");
    }

    @Test
    void aFreeUnitCanBeClaimed() {
        assertTrue(claimer.tryClaim("u1", "c1"));
        assertEquals(Optional.of("c1"), claimer.claimedBy("u1"));
    }

    @Test
    void aDifferentCaseCannotClaimAClaimedUnit() {
        claimer.tryClaim("u1", "c1");

        assertFalse(claimer.tryClaim("u1", "c2"));
        assertEquals(Optional.of("c1"), claimer.claimedBy("u1"));
    }

    /** Claiming again for the same case is harmless (a retried dispatch step). */
    @Test
    void theSameCaseClaimingAgainSucceeds() {
        claimer.tryClaim("u1", "c1");

        assertTrue(claimer.tryClaim("u1", "c1"));
    }

    @Test
    void differentUnitsAreClaimedIndependently() {
        assertTrue(claimer.tryClaim("u1", "c1"));
        assertTrue(claimer.tryClaim("u2", "c2"));

        assertEquals(Optional.of("c1"), claimer.claimedBy("u1"));
        assertEquals(Optional.of("c2"), claimer.claimedBy("u2"));
    }

    @Test
    void anUnclaimedUnitHasNoHolder() {
        assertEquals(Optional.empty(), claimer.claimedBy("u1"));
    }

    @Test
    void theOwnerCanReleaseAndSomeoneElseCanThenClaim() {
        claimer.tryClaim("u1", "c1");

        assertTrue(claimer.release("u1", "c1"));
        assertEquals(Optional.empty(), claimer.claimedBy("u1"));
        assertTrue(claimer.tryClaim("u1", "c2"));
    }

    @Test
    void aNonOwnerCannotRelease() {
        claimer.tryClaim("u1", "c1");

        assertFalse(claimer.release("u1", "c2"));
        assertEquals(Optional.of("c1"), claimer.claimedBy("u1"));
    }

    @Test
    void releasingAnUnclaimedUnitReturnsFalse() {
        assertFalse(claimer.release("u1", "c1"));
    }

    /** The one difference from the in-memory claimer: there is no row to update, so the claim fails. */
    @Test
    void aUnitThatDoesNotExistCannotBeClaimed() {
        assertFalse(claimer.tryClaim("ghost", "c1"));
        assertEquals(Optional.empty(), claimer.claimedBy("ghost"));
    }

    @Test
    void nullOrBlankIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim(null, "c1"));
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim("u1", " "));
        assertThrows(IllegalArgumentException.class, () -> claimer.release("", "c1"));
        assertThrows(IllegalArgumentException.class, () -> claimer.release("u1", null));
        assertThrows(IllegalArgumentException.class, () -> claimer.claimedBy(" "));
    }

    /**
     * 20 threads, each for its own case and on its own connection, claim u1 at the same moment (a latch opens the
     * gate for all of them together). Postgres' row lock lets exactly one win; the holder is the winner.
     */
    @Test
    void onlyOneOfManyConcurrentClaimersWins() throws Exception {
        int claimers = 20;
        List<String> caseIds = new ArrayList<>();
        for (int i = 0; i < claimers; i++) {
            String caseId = "race-" + i;
            TestDatabase.openCase(caseId);
            caseIds.add(caseId);
        }

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (String caseId : caseIds) {
                results.add(pool.submit(() -> {
                    gate.await();
                    return claimer.tryClaim("u1", caseId);
                }));
            }
            gate.countDown();

            List<String> winners = new ArrayList<>();
            for (int i = 0; i < claimers; i++) {
                if (results.get(i).get(10, TimeUnit.SECONDS)) {
                    winners.add(caseIds.get(i));
                }
            }
            assertEquals(1, winners.size());
            assertEquals(Optional.of(winners.get(0)), claimer.claimedBy("u1"));
        } finally {
            pool.shutdown();
        }
    }
}

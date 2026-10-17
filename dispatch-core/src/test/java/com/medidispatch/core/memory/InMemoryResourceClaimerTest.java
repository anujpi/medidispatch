package com.medidispatch.core.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryResourceClaimerTest {

    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();

    // ---------- claiming ----------

    @Test
    void aFreeResourceCanBeClaimed() {
        assertTrue(claimer.tryClaim("r1", "case-1"));

        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void aDifferentCaseCannotClaimAClaimedResource() {
        claimer.tryClaim("r1", "case-1");

        assertFalse(claimer.tryClaim("r1", "case-2"));

        assertEquals("case-1", claimer.claimedBy("r1").get());   // holder unchanged
    }

    @Test
    void theSameCaseClaimingAgainSucceeds() {                     // idempotent: a retry doesn't fail
        assertTrue(claimer.tryClaim("r1", "case-1"));
        assertTrue(claimer.tryClaim("r1", "case-1"));

        assertEquals("case-1", claimer.claimedBy("r1").get());
    }

    @Test
    void differentResourcesAreClaimedIndependently() {
        assertTrue(claimer.tryClaim("r1", "case-1"));
        assertTrue(claimer.tryClaim("r2", "case-2"));

        assertEquals("case-1", claimer.claimedBy("r1").get());
        assertEquals("case-2", claimer.claimedBy("r2").get());
    }

    @Test
    void anUnclaimedResourceHasNoHolder() {
        assertTrue(claimer.claimedBy("r1").isEmpty());
    }

    // ---------- releasing ----------

    @Test
    void theOwnerCanReleaseAndSomeoneElseCanThenClaim() {
        claimer.tryClaim("r1", "case-1");

        assertTrue(claimer.release("r1", "case-1"));

        assertTrue(claimer.claimedBy("r1").isEmpty());
        assertTrue(claimer.tryClaim("r1", "case-2"));
    }

    @Test
    void aNonOwnerCannotRelease() {
        claimer.tryClaim("r1", "case-1");

        assertFalse(claimer.release("r1", "case-2"));

        assertEquals("case-1", claimer.claimedBy("r1").get());   // claim stays
    }

    @Test
    void releasingAnUnclaimedResourceReturnsFalse() {
        assertFalse(claimer.release("r1", "case-1"));
    }

    // ---------- validation ----------

    @Test
    void nullOrBlankIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim(null, "case-1"));
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim("r1", null));
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim("  ", "case-1"));
        assertThrows(IllegalArgumentException.class, () -> claimer.tryClaim("r1", " "));
        assertThrows(IllegalArgumentException.class, () -> claimer.release(null, "case-1"));
        assertThrows(IllegalArgumentException.class, () -> claimer.release("r1", null));
        assertThrows(IllegalArgumentException.class, () -> claimer.claimedBy(null));
        assertThrows(IllegalArgumentException.class, () -> claimer.claimedBy(" "));
    }

    // ---------- the concurrency test ----------

    @Test
    void onlyOneOfManyConcurrentClaimersWins() throws Exception {
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch allReady = new CountDownLatch(threads);   // counts threads that are set up
        CountDownLatch startGun = new CountDownLatch(1);         // all threads wait for this
        AtomicInteger winners = new AtomicInteger();             // thread-safe counter
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String caseId = "case-" + i;
            futures.add(pool.submit(() -> {
                allReady.countDown();                            // "I'm ready"
                startGun.await();                                // wait for the signal
                if (claimer.tryClaim("r1", caseId)) {
                    winners.incrementAndGet();                   // count the winners
                }
                return null;
            }));
        }

        allReady.await();                                        // wait until every thread is waiting
        startGun.countDown();                                    // release them all at once

        for (Future<?> f : futures) {
            f.get();                                             // wait for each thread to finish
        }
        pool.shutdown();

        assertEquals(1, winners.get());                          // exactly one claim succeeded
        assertTrue(claimer.claimedBy("r1").isPresent());
    }
}
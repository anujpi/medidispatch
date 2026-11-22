package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcCaseQueue} on a real Postgres (the shared {@link TestDatabase}); mirrors InMemoryCaseQueueTest, plus what
 * the database adds: a case must be saved before it is queued, saving never undoes the flag, and two workers
 * polling at once never wait for or take the same case (FOR UPDATE SKIP LOCKED).
 *
 * <p>The queue is a flag on saved cases, so every test saves its cases first ({@link #saved}).
 */
class JdbcCaseQueueTest {

    private static final Instant T0 = Instant.parse("2026-11-22T12:00:00Z");

    private JdbcCaseRepository cases;
    private JdbcPatientRepository patients;
    private JdbcCaseQueue queue;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset();
        cases = new JdbcCaseRepository(TestDatabase.jdbc());
        patients = new JdbcPatientRepository(TestDatabase.jdbc());
        queue = new JdbcCaseQueue(TestDatabase.jdbc());
    }

    // ---- empty ----

    @Test
    void emptyQueueReturnsNothing() {
        assertTrue(queue.pollNext().isEmpty());
        assertEquals(0, queue.size());
    }

    // ---- ordering ----

    @Test
    void higherBandComesOutFirstRegardlessOfInsertionOrder() {
        queue.enqueue(saved("low", RiskBand.LOW, T0));
        queue.enqueue(saved("crit", RiskBand.CRITICAL, T0));
        queue.enqueue(saved("med", RiskBand.MEDIUM, T0));
        queue.enqueue(saved("high", RiskBand.HIGH, T0));

        assertEquals("crit", queue.pollNext().orElseThrow().id());
        assertEquals("high", queue.pollNext().orElseThrow().id());
        assertEquals("med", queue.pollNext().orElseThrow().id());
        assertEquals("low", queue.pollNext().orElseThrow().id());
    }

    @Test
    void sameBandOlderCaseComesFirst() {
        queue.enqueue(saved("newer", RiskBand.HIGH, T0.plusSeconds(60)));
        queue.enqueue(saved("older", RiskBand.HIGH, T0));

        assertEquals("older", queue.pollNext().orElseThrow().id());
        assertEquals("newer", queue.pollNext().orElseThrow().id());
    }

    @Test
    void sameBandAndTimeIsOrderedById() {
        queue.enqueue(saved("b", RiskBand.HIGH, T0));
        queue.enqueue(saved("a", RiskBand.HIGH, T0));

        assertEquals("a", queue.pollNext().orElseThrow().id());
        assertEquals("b", queue.pollNext().orElseThrow().id());
    }

    /** A newer critical outranks an older low. */
    @Test
    void bandBeatsAge() {
        queue.enqueue(saved("old-low", RiskBand.LOW, T0));
        queue.enqueue(saved("new-crit", RiskBand.CRITICAL, T0.plusSeconds(3600)));

        assertEquals("new-crit", queue.pollNext().orElseThrow().id());
    }

    // ---- poll / size ----

    @Test
    void pollRemovesTheCaseAndSizeGoesDown() {
        queue.enqueue(saved("c1", RiskBand.HIGH, T0));
        queue.enqueue(saved("c2", RiskBand.LOW, T0));
        assertEquals(2, queue.size());

        queue.pollNext();

        assertEquals(1, queue.size());
    }

    /** The polled case comes back as saved (every field), not just its id. */
    @Test
    void pollReturnsTheCaseAsSaved() {
        Case c = saved("c1", RiskBand.CRITICAL, T0);
        queue.enqueue(c);

        Case polled = queue.pollNext().orElseThrow();
        assertEquals("c1", polled.id());
        assertEquals("p-c1", polled.patientId());
        assertEquals(c.status(), polled.status());
        assertEquals(RiskBand.CRITICAL, polled.band());
        assertEquals(T0, polled.openedAt());
    }

    @Test
    void aPolledCaseCanBeQueuedAgain() {
        Case c = saved("c1", RiskBand.HIGH, T0);
        queue.enqueue(c);
        queue.pollNext();

        queue.enqueue(c);

        assertEquals(1, queue.size());
        assertEquals("c1", queue.pollNext().orElseThrow().id());
        assertTrue(queue.pollNext().isEmpty());
    }

    // ---- duplicates / re-ranking ----

    @Test
    void enqueueingTheSameCaseTwiceKeepsOneEntry() {
        Case c = saved("c1", RiskBand.LOW, T0);
        queue.enqueue(c);
        queue.enqueue(c);

        assertEquals(1, queue.size());
        queue.pollNext();
        assertTrue(queue.pollNext().isEmpty());
    }

    /** The engine's way: the patient got worse, the case is saved with its new band and queued again. */
    @Test
    void reEnqueueingAfterABandChangeReRanksTheCase() {
        Case low = saved("low", RiskBand.LOW, T0);
        Case high = saved("high", RiskBand.HIGH, T0);
        queue.enqueue(low);
        queue.enqueue(high);

        low.updateBand(RiskBand.CRITICAL);
        cases.save(low);
        queue.enqueue(low);

        assertEquals("low", queue.pollNext().orElseThrow().id());
        assertEquals("high", queue.pollNext().orElseThrow().id());
    }

    /**
     * Replaces the in-memory "snapshot" test: here a queued case is ranked by the band saved NOW (one row, one band).
     * The engine always re-queues when a band changes, so it sees no difference.
     */
    @Test
    void theQueueRanksByTheSavedBand() {
        Case low = saved("low", RiskBand.LOW, T0);
        Case high = saved("high", RiskBand.HIGH, T0);
        queue.enqueue(low);
        queue.enqueue(high);

        low.updateBand(RiskBand.CRITICAL);
        cases.save(low);

        assertEquals("low", queue.peekNext().orElseThrow().id());
    }

    // ---- remove ----

    @Test
    void removeTakesTheCaseOutAndReturnsTrue() {
        queue.enqueue(saved("c1", RiskBand.HIGH, T0));
        queue.enqueue(saved("c2", RiskBand.LOW, T0));

        assertTrue(queue.remove("c1"));

        assertEquals(1, queue.size());
        assertEquals("c2", queue.pollNext().orElseThrow().id());
    }

    @Test
    void removingAnUnknownIdReturnsFalse() {
        queue.enqueue(saved("c1", RiskBand.HIGH, T0));

        assertFalse(queue.remove("nope"));

        assertEquals(1, queue.size());
    }

    @Test
    void removingASavedButUnqueuedCaseReturnsFalse() {
        saved("c1", RiskBand.HIGH, T0);

        assertFalse(queue.remove("c1"));
    }

    // ---- what the database adds ----

    /** The update would change nothing: fail loudly instead of silently losing an emergency. */
    @Test
    void aCaseThatWasNeverSavedCannotBeQueued() {
        Case unsaved = new Case("ghost", "p-ghost", RiskBand.CRITICAL, T0);

        assertThrows(IllegalStateException.class, () -> queue.enqueue(unsaved));
        assertEquals(0, queue.size());
    }

    /** Column ownership, in CaseManager's order: enqueue first, then save the case. It must stay queued. */
    @Test
    void savingACaseLeavesItQueued() {
        Case c = saved("c1", RiskBand.HIGH, T0);

        queue.enqueue(c);
        c.updateBand(RiskBand.CRITICAL);
        cases.save(c);

        assertEquals(1, queue.size());
        assertEquals("c1", queue.pollNext().orElseThrow().id());
    }

    /**
     * FOR UPDATE SKIP LOCKED, live. Worker A polls inside a transaction it has not committed, so it holds the lock on
     * "first". Worker B polls at the same moment: it must neither wait for A nor get "first", but take "second" at
     * once. (If B waited, it would wait forever here, because A only commits afterwards; the timeout, on its own
     * thread, turns that into a failure.)
     */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSecondWorkerSkipsTheCaseTheFirstIsTakingInsteadOfWaiting() throws Exception {
        queue.enqueue(saved("first", RiskBand.CRITICAL, T0));
        queue.enqueue(saved("second", RiskBand.HIGH, T0));

        // One connection that stays open, with auto-commit off: everything A does is one transaction until commit.
        SingleConnectionDataSource held = new SingleConnectionDataSource(
                TestDatabase.url(), TestDatabase.username(), TestDatabase.password(), true);
        held.setAutoCommit(false);
        try {
            JdbcCaseQueue workerA = new JdbcCaseQueue(JdbcClient.create(held));
            assertEquals("first", workerA.pollNext().orElseThrow().id());

            assertEquals("second", queue.pollNext().orElseThrow().id());

            held.getConnection().commit();
        } finally {
            held.destroy();
        }
        assertTrue(queue.pollNext().isEmpty());
    }

    // ---- validation ----

    @Test
    void nullCaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(null));
    }

    @Test
    void nullIdOnRemoveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> queue.remove(null));
    }

    // ---- peekNext ----

    @Test
    void peekOnAnEmptyQueueReturnsNothing() {
        assertTrue(queue.peekNext().isEmpty());
    }

    @Test
    void peekShowsTheMostUrgentCaseWithoutRemovingIt() {
        queue.enqueue(saved("low", RiskBand.LOW, T0));
        queue.enqueue(saved("crit", RiskBand.CRITICAL, T0));

        assertEquals("crit", queue.peekNext().orElseThrow().id());
        assertEquals("crit", queue.peekNext().orElseThrow().id());
        assertEquals(2, queue.size());
    }

    @Test
    void peekFollowsTheSameOrderAsPoll() {
        queue.enqueue(saved("high", RiskBand.HIGH, T0));
        queue.enqueue(saved("crit", RiskBand.CRITICAL, T0));

        assertEquals("crit", queue.peekNext().orElseThrow().id());
        assertEquals("crit", queue.pollNext().orElseThrow().id());
        assertEquals("high", queue.peekNext().orElseThrow().id());
    }

    // ---- helpers ----

    /** Saves a patient ("p-" + id) and an open case for it, and returns the case (not queued yet). */
    private Case saved(String id, RiskBand band, Instant openedAt) {
        patients.save(new Patient("p-" + id, new Location(28.6139, 77.2090)));
        Case c = new Case(id, "p-" + id, band, openedAt);
        cases.save(c);
        return c;
    }
}

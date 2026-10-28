package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryCaseQueueTest {

    private static final Instant T0 = Instant.parse("2026-10-07T12:00:00Z");

    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();

    private Case caseOf(String id, RiskBand band, Instant openedAt) {
        return new Case(id, "patient-" + id, band, openedAt);
    }

    // ---------- empty ----------

    @Test
    void emptyQueueReturnsNothing() {
        assertTrue(queue.pollNext().isEmpty());
        assertEquals(0, queue.size());
    }

    // ---------- ordering ----------

    @Test
    void higherBandComesOutFirstRegardlessOfInsertionOrder() {
        queue.enqueue(caseOf("low", RiskBand.LOW, T0));
        queue.enqueue(caseOf("crit", RiskBand.CRITICAL, T0));
        queue.enqueue(caseOf("med", RiskBand.MEDIUM, T0));
        queue.enqueue(caseOf("high", RiskBand.HIGH, T0));

        assertEquals("crit", queue.pollNext().get().id());
        assertEquals("high", queue.pollNext().get().id());
        assertEquals("med", queue.pollNext().get().id());
        assertEquals("low", queue.pollNext().get().id());
    }

    @Test
    void sameBandOlderCaseComesFirst() {
        queue.enqueue(caseOf("newer", RiskBand.HIGH, T0.plusSeconds(60)));
        queue.enqueue(caseOf("older", RiskBand.HIGH, T0));

        assertEquals("older", queue.pollNext().get().id());
        assertEquals("newer", queue.pollNext().get().id());
    }

    @Test
    void sameBandAndTimeIsOrderedById() {
        queue.enqueue(caseOf("b", RiskBand.HIGH, T0));
        queue.enqueue(caseOf("a", RiskBand.HIGH, T0));

        assertEquals("a", queue.pollNext().get().id());
        assertEquals("b", queue.pollNext().get().id());
    }

    @Test
    void bandBeatsAge() {                                  // a newer critical outranks an older low
        queue.enqueue(caseOf("old-low", RiskBand.LOW, T0));
        queue.enqueue(caseOf("new-crit", RiskBand.CRITICAL, T0.plusSeconds(3600)));

        assertEquals("new-crit", queue.pollNext().get().id());
    }

    // ---------- poll / size ----------

    @Test
    void pollRemovesTheCaseAndSizeGoesDown() {
        queue.enqueue(caseOf("c1", RiskBand.HIGH, T0));
        queue.enqueue(caseOf("c2", RiskBand.LOW, T0));
        assertEquals(2, queue.size());

        queue.pollNext();

        assertEquals(1, queue.size());                     // fails while byId isn't cleaned in pollNext
    }

    @Test
    void aPolledCaseCanBeQueuedAgain() {
        Case c = caseOf("c1", RiskBand.HIGH, T0);
        queue.enqueue(c);
        queue.pollNext();

        queue.enqueue(c);

        assertEquals(1, queue.size());
        assertEquals("c1", queue.pollNext().get().id());
        assertTrue(queue.pollNext().isEmpty());
    }

    // ---------- duplicates / re-ranking ----------

    @Test
    void enqueueingTheSameIdTwiceKeepsOneEntry() {
        queue.enqueue(caseOf("c1", RiskBand.LOW, T0));
        queue.enqueue(caseOf("c1", RiskBand.HIGH, T0));

        assertEquals(1, queue.size());
        assertEquals(RiskBand.HIGH, queue.pollNext().get().band());
        assertTrue(queue.pollNext().isEmpty());
    }

    @Test
    void reEnqueueingAfterABandChangeReRanksTheCase() {
        Case low = caseOf("low", RiskBand.LOW, T0);
        Case high = caseOf("high", RiskBand.HIGH, T0);
        queue.enqueue(low);
        queue.enqueue(high);

        low.updateBand(RiskBand.CRITICAL);                 // patient got worse
        queue.enqueue(low);                                // re-enqueue = re-rank

        assertEquals("low", queue.pollNext().get().id());
        assertEquals("high", queue.pollNext().get().id());
    }

    @Test
    void changingABandWithoutReEnqueueingDoesNotReorderTheQueue() {   // the snapshot, on purpose
        Case low = caseOf("low", RiskBand.LOW, T0);
        Case high = caseOf("high", RiskBand.HIGH, T0);
        queue.enqueue(low);
        queue.enqueue(high);

        low.updateBand(RiskBand.CRITICAL);                 // no re-enqueue

        assertEquals("high", queue.pollNext().get().id()); // queue still uses the snapshot
        assertEquals("low", queue.pollNext().get().id());
    }

    // ---------- remove ----------

    @Test
    void removeTakesTheCaseOutAndReturnsTrue() {
        queue.enqueue(caseOf("c1", RiskBand.HIGH, T0));
        queue.enqueue(caseOf("c2", RiskBand.LOW, T0));

        assertTrue(queue.remove("c1"));

        assertEquals(1, queue.size());
        assertEquals("c2", queue.pollNext().get().id());
    }

    @Test
    void removingAnUnknownIdReturnsFalse() {
        queue.enqueue(caseOf("c1", RiskBand.HIGH, T0));

        assertFalse(queue.remove("nope"));

        assertEquals(1, queue.size());
    }

    // ---------- validation ----------

    @Test
    void nullCaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(null));
    }

    @Test
    void nullIdOnRemoveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> queue.remove(null));
    }

    // ---------- peekNext ----------

    @Test
    void peekOnAnEmptyQueueReturnsNothing() {
        assertTrue(queue.peekNext().isEmpty());
    }

    @Test
    void peekShowsTheMostUrgentCaseWithoutRemovingIt() {
        queue.enqueue(caseOf("low", RiskBand.LOW, T0));
        queue.enqueue(caseOf("crit", RiskBand.CRITICAL, T0));

        assertEquals("crit", queue.peekNext().get().id());
        assertEquals("crit", queue.peekNext().get().id());     // still there
        assertEquals(2, queue.size());
    }

    @Test
    void peekFollowsTheSameOrderAsPoll() {
        queue.enqueue(caseOf("high", RiskBand.HIGH, T0));
        queue.enqueue(caseOf("crit", RiskBand.CRITICAL, T0));

        assertEquals("crit", queue.peekNext().get().id());
        assertEquals("crit", queue.pollNext().get().id());
        assertEquals("high", queue.peekNext().get().id());     // the next one is now in front
    }
}
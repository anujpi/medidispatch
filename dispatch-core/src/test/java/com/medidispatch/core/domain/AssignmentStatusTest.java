package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.medidispatch.core.domain.AssignmentStatus.*;
import static org.junit.jupiter.api.Assertions.*;

public class AssignmentStatusTest {

    // The transition table from the spec. This is the source of truth for the tests.
    private static final Map<AssignmentStatus, Set<AssignmentStatus>> ALLOWED = Map.of(
            ASSIGNED,  EnumSet.of(EN_ROUTE, CANCELLED, TIMED_OUT, FAILED),
            EN_ROUTE,  EnumSet.of(ARRIVED, CANCELLED, FAILED),
            ARRIVED,   EnumSet.of(DONE, CANCELLED, FAILED),
            DONE,      EnumSet.noneOf(AssignmentStatus.class),
            CANCELLED, EnumSet.noneOf(AssignmentStatus.class),
            TIMED_OUT, EnumSet.noneOf(AssignmentStatus.class),
            FAILED,    EnumSet.noneOf(AssignmentStatus.class)
    );

    @Test
    void everyPairOfStatusesMatchesTheTable() {
        for (AssignmentStatus from : AssignmentStatus.values()) {
            for (AssignmentStatus to : AssignmentStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertEquals(expected, from.canTransitionTo(to),
                        from + " -> " + to + " should be " + (expected ? "allowed" : "refused"));
            }
        }
    }

    @Test
    void happyPathIsAllowed() {
        assertTrue(ASSIGNED.canTransitionTo(EN_ROUTE));
        assertTrue(EN_ROUTE.canTransitionTo(ARRIVED));
        assertTrue(ARRIVED.canTransitionTo(DONE));
    }

    @Test
    void aTripCanBeCancelledUntilItIsFinished() {
        assertTrue(ASSIGNED.canTransitionTo(CANCELLED));
        assertTrue(EN_ROUTE.canTransitionTo(CANCELLED));
        assertTrue(ARRIVED.canTransitionTo(CANCELLED));
    }

    @Test
    void aTripCanFailAtAnyStageBeforeItIsFinished() {
        assertTrue(ASSIGNED.canTransitionTo(FAILED));
        assertTrue(EN_ROUTE.canTransitionTo(FAILED));
        assertTrue(ARRIVED.canTransitionTo(FAILED));
    }

    @Test
    void onlyATripThatNeverAnsweredCanTimeOut() {
        assertTrue(ASSIGNED.canTransitionTo(TIMED_OUT));
        assertFalse(EN_ROUTE.canTransitionTo(TIMED_OUT));
        assertFalse(ARRIVED.canTransitionTo(TIMED_OUT));
    }

    @Test
    void skippingStepsIsRefused() {
        assertFalse(ASSIGNED.canTransitionTo(ARRIVED));
        assertFalse(ASSIGNED.canTransitionTo(DONE));
        assertFalse(EN_ROUTE.canTransitionTo(DONE));
    }

    @Test
    void goingBackwardsIsRefused() {
        assertFalse(EN_ROUTE.canTransitionTo(ASSIGNED));
        assertFalse(ARRIVED.canTransitionTo(EN_ROUTE));
        assertFalse(DONE.canTransitionTo(ARRIVED));
    }

    @Test
    void aStatusCannotTransitionToItself() {
        for (AssignmentStatus s : AssignmentStatus.values()) {
            assertFalse(s.canTransitionTo(s), s + " -> " + s);
        }
    }

    @Test
    void nothingLeavesATerminalStatus() {
        for (AssignmentStatus to : AssignmentStatus.values()) {
            assertFalse(DONE.canTransitionTo(to));
            assertFalse(CANCELLED.canTransitionTo(to));
            assertFalse(TIMED_OUT.canTransitionTo(to));
            assertFalse(FAILED.canTransitionTo(to));
        }
    }

    @Test
    void onlyDoneCancelledTimedOutAndFailedAreTerminal() {
        assertTrue(DONE.isTerminal());
        assertTrue(CANCELLED.isTerminal());
        assertTrue(TIMED_OUT.isTerminal());
        assertTrue(FAILED.isTerminal());
        assertFalse(ASSIGNED.isTerminal());
        assertFalse(EN_ROUTE.isTerminal());
        assertFalse(ARRIVED.isTerminal());
    }
}
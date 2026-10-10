package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.medidispatch.core.domain.CaseStatus.*;
import static org.junit.jupiter.api.Assertions.*;

public class CaseStatusTest {
    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = Map.of(
            MONITORING, EnumSet.of(ALERTED, CANCELLED),
            ALERTED,    EnumSet.of(RESPONDING, MONITORING, CANCELLED),
            RESPONDING, EnumSet.of(STABILISED, ALERTED, CANCELLED),
            STABILISED, EnumSet.of(CLOSED, ALERTED),
            CLOSED,     EnumSet.noneOf(CaseStatus.class),
            CANCELLED,  EnumSet.noneOf(CaseStatus.class)
    );
    @Test
    void everyPairOfStatusesMatchesTheTable() {
        for (CaseStatus from : CaseStatus.values()) {
            for (CaseStatus to : CaseStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertEquals(expected, from.canTransitionTo(to),
                        from + " -> " + to + " should be " + (expected ? "allowed" : "refused"));
            }
        }
    }
    @Test
    void happyPathIsAllowed() {
        assertTrue(MONITORING.canTransitionTo(ALERTED));
        assertTrue(ALERTED.canTransitionTo(RESPONDING));
        assertTrue(RESPONDING.canTransitionTo(STABILISED));
        assertTrue(STABILISED.canTransitionTo(CLOSED));
    }

    @Test
    void backEdgesAreAllowed() {
        assertTrue(ALERTED.canTransitionTo(MONITORING));    // risk dropped before help was sent
        assertTrue(RESPONDING.canTransitionTo(ALERTED));    // responder lost, re-dispatch
        assertTrue(STABILISED.canTransitionTo(ALERTED));    // relapse
    }

    @Test
    void skippingStepsIsRefused() {
        assertFalse(MONITORING.canTransitionTo(RESPONDING));
        assertFalse(MONITORING.canTransitionTo(STABILISED));
        assertFalse(RESPONDING.canTransitionTo(CLOSED));
    }

    @Test
    void aStatusCannotTransitionToItself() {
        for (CaseStatus s : CaseStatus.values()) {
            assertFalse(s.canTransitionTo(s), s + " -> " + s);
        }
    }

    @Test
    void nothingLeavesATerminalStatus() {
        for (CaseStatus to : CaseStatus.values()) {
            assertFalse(CLOSED.canTransitionTo(to));
            assertFalse(CANCELLED.canTransitionTo(to));
        }
    }

    @Test
    void onlyClosedAndCancelledAreTerminal() {
        assertTrue(CLOSED.isTerminal());
        assertTrue(CANCELLED.isTerminal());
        assertFalse(MONITORING.isTerminal());
        assertFalse(ALERTED.isTerminal());
        assertFalse(RESPONDING.isTerminal());
        assertFalse(STABILISED.isTerminal());
    }
}
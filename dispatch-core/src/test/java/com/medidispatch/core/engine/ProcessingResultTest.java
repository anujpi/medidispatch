package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.engine.ProcessingResult.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessingResultTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final RiskAssessment ASSESSMENT = new RiskAssessment("p1", 9, RiskBand.CRITICAL, List.of(), NOW);
    private static final Case CASE = new Case("c1", "p1", RiskBand.CRITICAL, NOW);

    @Test
    void anAcceptedResultCarriesTheAssessmentAndTheCase() {
        ProcessingResult r = ProcessingResult.accepted(ASSESSMENT, Optional.of(CASE));

        assertTrue(r.isAccepted());
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertEquals(ASSESSMENT, r.assessment().get());
        assertEquals(CASE, r.currentCase().get());
    }

    @Test
    void anAcceptedResultMayHaveNoCase() {                        // a healthy patient has no open case
        ProcessingResult r = ProcessingResult.accepted(ASSESSMENT, Optional.empty());

        assertTrue(r.isAccepted());
        assertTrue(r.currentCase().isEmpty());
    }

    @Test
    void aRejectedResultCarriesNothing() {
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.ACCEPTED) {
                continue;
            }
            ProcessingResult r = ProcessingResult.rejected(outcome);

            assertFalse(r.isAccepted());
            assertEquals(outcome, r.outcome());
            assertTrue(r.assessment().isEmpty());
            assertTrue(r.currentCase().isEmpty());
        }
    }

    @Test
    void acceptedCannotBeUsedAsARejection() {
        assertThrows(IllegalArgumentException.class, () -> ProcessingResult.rejected(Outcome.ACCEPTED));
    }

    @Test
    void anAcceptedResultWithoutAnAssessmentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ProcessingResult.accepted(null, Optional.empty()));
    }

    @Test
    void impossibleCombinationsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingResult(Outcome.DUPLICATE, Optional.of(ASSESSMENT), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingResult(Outcome.TOO_OLD, Optional.empty(), Optional.of(CASE)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingResult(null, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingResult(Outcome.ACCEPTED, null, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessingResult(Outcome.ACCEPTED, Optional.of(ASSESSMENT), null));
    }
}

package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.RiskAssessment;

import java.util.Optional;

/**
 * What happened to one vitals reading handed to {@link VitalsProcessor}.
 * <p>
 * A rejected reading is a normal event with device data (repeats, late arrivals, bad clocks), not an error,
 * so it is reported here instead of thrown. Only an {@link Outcome#ACCEPTED} reading carries an assessment.
 *
 * @param outcome     what happened to the reading
 * @param assessment  the risk assessment; present only when the reading was accepted
 * @param currentCase the patient's case after the reading; present only when accepted and the patient has
 *                    an open case
 */
public record ProcessingResult(Outcome outcome, Optional<RiskAssessment> assessment, Optional<Case> currentCase) {

    /** What happened to a reading. Everything except {@link #ACCEPTED} means it was ignored. */
    public enum Outcome {
        /** Stored, scored and applied to the patient's case. */
        ACCEPTED,
        /** No registered patient has this id (for example, a misconfigured device). */
        UNKNOWN_PATIENT,
        /** Timed further ahead of the engine's clock than device clock drift explains. */
        FROM_THE_FUTURE,
        /** Taken too long ago to say anything about the patient now. */
        TOO_OLD,
        /** Older than a reading already received for the patient; it must not overwrite newer data. */
        OUT_OF_ORDER,
        /** Same patient and same time as a reading already received (for example, a redelivered message). */
        DUPLICATE
    }

    /**
     * Validates the result.
     *
     * @throws IllegalArgumentException if a field is missing, an accepted result has no assessment,
     *                                  or a rejected result carries an assessment or a case
     */
    public ProcessingResult {
        if (outcome == null || assessment == null || currentCase == null) {
            throw new IllegalArgumentException("outcome, assessment and currentCase are required (use Optional.empty())");
        }
        if (outcome == Outcome.ACCEPTED && assessment.isEmpty()) {
            throw new IllegalArgumentException("an accepted reading must have an assessment");
        }
        if (outcome != Outcome.ACCEPTED && (assessment.isPresent() || currentCase.isPresent())) {
            throw new IllegalArgumentException("a rejected reading has no assessment and no case");
        }
    }

    /**
     * The result for a reading that was stored, scored and applied.
     *
     * @param assessment  the reading's risk assessment
     * @param currentCase the patient's case afterwards, or empty if the patient has none
     * @return an {@link Outcome#ACCEPTED} result
     */
    public static ProcessingResult accepted(RiskAssessment assessment, Optional<Case> currentCase) {
        return new ProcessingResult(Outcome.ACCEPTED, Optional.ofNullable(assessment), currentCase);
    }

    /**
     * The result for a reading that was ignored.
     *
     * @param outcome why it was ignored; anything except {@link Outcome#ACCEPTED}
     * @return a result with no assessment and no case
     * @throws IllegalArgumentException if {@code outcome} is {@code ACCEPTED} or missing
     */
    public static ProcessingResult rejected(Outcome outcome) {
        if (outcome == Outcome.ACCEPTED) {
            throw new IllegalArgumentException("use accepted(...) for an accepted reading");
        }
        return new ProcessingResult(outcome, Optional.empty(), Optional.empty());
    }

    /**
     * Says whether the reading was used.
     *
     * @return {@code true} if the outcome is {@link Outcome#ACCEPTED}
     */
    public boolean isAccepted() {
        return outcome == Outcome.ACCEPTED;
    }
}

package com.medidispatch.core.scoring;

import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.VitalsReading;

/**
 * Strategy: turns a vitals reading into a risk assessment (score, band and reasons).
 * <p>
 * The rest of the engine only knows this interface, so scorers can be swapped without changing it:
 * {@link SimpleScorer} today, a NEWS2-based scorer (plus trend and patient baseline) later, and several
 * side by side in the simulator for comparison.
 */
public interface RiskScorer {

    /**
     * Scores one reading.
     *
     * @param reading the vitals to score
     * @return the assessment for that reading's patient
     */
    RiskAssessment assess(VitalsReading reading);
}

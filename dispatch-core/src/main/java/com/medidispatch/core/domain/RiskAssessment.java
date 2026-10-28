package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.List;

/**
 * The result of scoring a patient's vitals: a score, the urgency band it falls into, and why.
 *
 * @param patientId  the patient that was scored; never blank
 * @param score      the raw score from a {@link com.medidispatch.core.scoring.RiskScorer}; never negative
 * @param band       the urgency band derived from the score
 * @param factors    human-readable reasons behind the score, stored as an immutable copy
 * @param computedAt when the assessment was made
 */
public record RiskAssessment(String patientId,
                             int score,
                             RiskBand band,
                             List<String> factors,
                             Instant computedAt) {

    /**
     * Validates the assessment and makes {@code factors} immutable.
     *
     * @throws IllegalArgumentException if any field is missing, the patient id is blank or the score is negative
     */
    public RiskAssessment{
        if(patientId == null || patientId.isBlank()){
            throw new IllegalArgumentException("PatientId cant be null or blank");
        }
        if(score < 0){
            throw new IllegalArgumentException("Score cant be less than 0");
        }
        if(band == null){
            throw new IllegalArgumentException("Band cant be Null");
        }
        if(factors == null){
            throw new IllegalArgumentException("Factors cant be Null");
        }
        if(computedAt == null){
            throw new IllegalArgumentException("Compute At cant be Null");
        }
        factors = List.copyOf(factors);
    }

}

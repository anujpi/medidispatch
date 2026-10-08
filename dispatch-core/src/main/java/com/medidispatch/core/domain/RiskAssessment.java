package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.List;

public record RiskAssessment(String patientId,
                             int score,
                             RiskBand band,
                             List<String> factors,
                             Instant computedAt) {

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

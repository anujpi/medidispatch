package com.medidispatch.core.scoring;

import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.VitalsReading;

public interface RiskScorer {
    RiskAssessment assess(VitalsReading reading);
}

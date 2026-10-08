package com.medidispatch.core.scoring;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public class SimpleScorer implements RiskScorer {

    private static final int SPO2_LOW = 94;
    private static final int HEART_RATE_HIGH = 110;
    private static final int HEART_RATE_LOW = 50;
    private static final int RESP_RATE_HIGH = 24;
    private static final int SYSTOLIC_LOW = 100;
    private static final double TEMPERATURE_HIGH = 38.0;

    private final Clock clock;

    public SimpleScorer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RiskAssessment assess(VitalsReading reading) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        Integer spo2 = reading.spo2();
        if (spo2 != null && spo2 < SPO2_LOW) {
            score += 2;
            factors.add("SpO2 " + spo2 + " below " + SPO2_LOW + " (+2)");
        }

        Integer hr = reading.heartRate();
        if (hr != null && (hr < HEART_RATE_LOW || hr > HEART_RATE_HIGH)) {
            score += 2;
            factors.add(hr < HEART_RATE_LOW
                    ? "Heart rate " + hr + " below " + HEART_RATE_LOW + " (+2)"
                    : "Heart rate " + hr + " above " + HEART_RATE_HIGH + " (+2)");
        }

        Integer rr = reading.respRate();
        if (rr != null && rr > RESP_RATE_HIGH) {
            score += 2;
            factors.add("Resp rate " + rr + " above " + RESP_RATE_HIGH + " (+2)");
        }

        Integer sys = reading.systolicBp();
        if (sys != null && sys < SYSTOLIC_LOW) {
            score += 2;
            factors.add("Systolic BP " + sys + " below " + SYSTOLIC_LOW + " (+2)");
        }

        Double temp = reading.temperature();
        if (temp != null && temp > TEMPERATURE_HIGH) {
            score += 1;
            factors.add("Temperature " + temp + " above " + TEMPERATURE_HIGH + " (+1)");
        }

        if (reading.consciousness() != Consciousness.ALERT) {
            score += 3;
            factors.add("Consciousness " + reading.consciousness() + " (+3)");
        }

        return new RiskAssessment(reading.patientId(), score, toBand(score), factors, clock.instant());
    }

    private RiskBand toBand(int score) {
        if (score >= 7) return RiskBand.CRITICAL;
        if (score >= 5) return RiskBand.HIGH;
        if (score >= 3) return RiskBand.MEDIUM;
        return RiskBand.LOW;
    }
}
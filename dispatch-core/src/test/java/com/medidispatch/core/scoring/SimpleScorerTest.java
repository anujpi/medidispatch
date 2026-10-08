package com.medidispatch.core.scoring;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleScorerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private final SimpleScorer scorer = new SimpleScorer(Clock.fixed(NOW, ZoneOffset.UTC));

    // Order: hr, spo2, sys, rr, temp, consciousness. Diastolic is always null here.
    private VitalsReading reading(Integer hr, Integer spo2, Integer sys, Integer rr,
                                  Double temp, Consciousness c) {
        return new VitalsReading("p1", Instant.now(), hr, spo2, sys, null, rr, temp, c);
    }

    private VitalsReading healthy() {
        return reading(80, 96, 120, 16, 36.8, Consciousness.ALERT);
    }

    // healthy / missing
    @Test
    void healthyReadingScoresZeroAndIsLow() {
        RiskAssessment a = scorer.assess(healthy());

        assertEquals(0, a.score());
        assertEquals(RiskBand.LOW, a.band());
        assertTrue(a.factors().isEmpty());
    }

    @Test
    void allOptionalValuesMissingScoresZero() {
        RiskAssessment a = scorer.assess(reading(null, null, null, null, null, Consciousness.ALERT));

        assertEquals(0, a.score());
        assertEquals(RiskBand.LOW, a.band());
    }

    @Test
    void valuesExactlyOnTheThresholdDoNotScore() {
        RiskAssessment a = scorer.assess(reading(110, 94, 100, 24, 38.0, Consciousness.ALERT));
        assertEquals(0, a.score());

        RiskAssessment b = scorer.assess(reading(50, 94, 100, 24, 38.0, Consciousness.ALERT));
        assertEquals(0, b.score());
    }

    //each rule on its own

    @Test
    void lowSpo2Adds2() {
        RiskAssessment a = scorer.assess(reading(80, 88, 120, 16, 36.8, Consciousness.ALERT));

        assertEquals(2, a.score());
        assertEquals(List.of("SpO2 88 below 94 (+2)"), a.factors());
    }

    @Test
    void highHeartRateAdds2() {
        RiskAssessment a = scorer.assess(reading(120, 96, 120, 16, 36.8, Consciousness.ALERT));

        assertEquals(2, a.score());
        assertEquals(List.of("Heart rate 120 above 110 (+2)"), a.factors());
    }

    @Test
    void lowHeartRateAdds2() {
        RiskAssessment a = scorer.assess(reading(40, 96, 120, 16, 36.8, Consciousness.ALERT));

        assertEquals(2, a.score());
        assertEquals(List.of("Heart rate 40 below 50 (+2)"), a.factors());
    }

    @Test
    void highRespRateAdds2() {
        RiskAssessment a = scorer.assess(reading(80, 96, 120, 30, 36.8, Consciousness.ALERT));

        assertEquals(2, a.score());
        assertEquals(List.of("Resp rate 30 above 24 (+2)"), a.factors());
    }

    @Test
    void lowSystolicAdds2() {
        RiskAssessment a = scorer.assess(reading(80, 96, 85, 16, 36.8, Consciousness.ALERT));

        assertEquals(2, a.score());
        assertEquals(List.of("Systolic BP 85 below 100 (+2)"), a.factors());
    }

    @Test
    void highTemperatureAdds1() {
        RiskAssessment a = scorer.assess(reading(80, 96, 120, 16, 39.0, Consciousness.ALERT));

        assertEquals(1, a.score());
        assertEquals(List.of("Temperature 39.0 above 38.0 (+1)"), a.factors());
    }

    @Test
    void reducedConsciousnessAdds3() {
        RiskAssessment a = scorer.assess(reading(80, 96, 120, 16, 36.8, Consciousness.CONFUSED));

        assertEquals(3, a.score());
        assertEquals(List.of("Consciousness CONFUSED (+3)"), a.factors());
    }

    //combinations

    @Test
    void severalBadVitalsAddUp() {
        // SpO2 low (+2), heart rate high (+2), confused (+3) = 7
        RiskAssessment a = scorer.assess(reading(120, 88, 120, 16, 36.8, Consciousness.CONFUSED));

        assertEquals(7, a.score());
        assertEquals(RiskBand.CRITICAL, a.band());
        assertEquals(3, a.factors().size());
    }

    @Test
    void everyRuleFiringGivesTwelve() {
        RiskAssessment a = scorer.assess(reading(120, 88, 85, 30, 39.0, Consciousness.UNRESPONSIVE));

        assertEquals(12, a.score());
        assertEquals(RiskBand.CRITICAL, a.band());
        assertEquals(6, a.factors().size());
    }

    //band boundaries

    @Test
    void scoreTwoIsLow() {
        // spo2 (+2)
        assertEquals(RiskBand.LOW,
                scorer.assess(reading(80, 88, 120, 16, 36.8, Consciousness.ALERT)).band());
    }

    @Test
    void scoreThreeIsMedium() {
        // confused (+3)
        assertEquals(RiskBand.MEDIUM,
                scorer.assess(reading(80, 96, 120, 16, 36.8, Consciousness.CONFUSED)).band());
    }

    @Test
    void scoreFourIsMedium() {
        // spo2 (+2), heart rate (+2)
        assertEquals(RiskBand.MEDIUM,
                scorer.assess(reading(120, 88, 120, 16, 36.8, Consciousness.ALERT)).band());
    }

    @Test
    void scoreFiveIsHigh() {
        // spo2 (+2), confused (+3)
        assertEquals(RiskBand.HIGH,
                scorer.assess(reading(80, 88, 120, 16, 36.8, Consciousness.CONFUSED)).band());
    }

    @Test
    void scoreSixIsHigh() {
        // spo2 (+2), heart rate (+2), resp rate (+2)
        assertEquals(RiskBand.HIGH,
                scorer.assess(reading(120, 88, 120, 30, 36.8, Consciousness.ALERT)).band());
    }

    @Test
    void scoreSevenIsCritical() {
        // spo2 (+2), heart rate (+2), resp rate (+2), temperature (+1)
        assertEquals(RiskBand.CRITICAL,
                scorer.assess(reading(120, 88, 120, 30, 39.0, Consciousness.ALERT)).band());
    }

    //output details

    @Test
    void computedAtComesFromTheClock() {
        assertEquals(NOW, scorer.assess(healthy()).computedAt());
    }

    @Test
    void patientIdIsCarriedOver() {
        assertEquals("p1", scorer.assess(healthy()).patientId());
    }
}
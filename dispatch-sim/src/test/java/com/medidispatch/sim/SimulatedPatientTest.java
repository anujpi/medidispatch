package com.medidispatch.sim;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.scoring.SimpleScorer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

public class SimulatedPatientTest {

    private static final Instant T0 = Instant.parse("2026-01-05T08:00:00Z");
    private static final Instant ILL_AT = T0.plusSeconds(600);
    private static final Instant FULLY_ILL = ILL_AT.plus(SimulatedPatient.RAMP);
    private static final Location HOME = new Location(12.97, 77.59);

    private final SimpleScorer scorer = new SimpleScorer(Clock.fixed(T0, ZoneOffset.UTC));

    private RiskBand bandOf(VitalsReading reading) {
        return scorer.assess(reading).band();
    }

    private SimulatedPatient fallsIllTo(double peak) {
        return new SimulatedPatient("p1", HOME, ILL_AT, peak);
    }

    @Test
    void aHealthyPatientScoresLowAllTheTime() {
        SimulatedPatient healthy = new SimulatedPatient("p1", HOME, null, SimulatedPatient.CRITICAL_PEAK);

        assertFalse(healthy.willDeteriorate());
        assertEquals(RiskBand.LOW, bandOf(healthy.readingAt(T0)));
        assertEquals(RiskBand.LOW, bandOf(healthy.readingAt(FULLY_ILL.plusSeconds(3600))));
    }

    @Test
    void beforeFallingIllThePatientIsHealthy() {
        SimulatedPatient patient = fallsIllTo(SimulatedPatient.CRITICAL_PEAK);

        assertEquals(0, patient.severityAt(ILL_AT.minusSeconds(1)));
        assertEquals(RiskBand.LOW, bandOf(patient.readingAt(ILL_AT.minusSeconds(1))));
    }

    // these three lock the peak values to the placeholder scorer: if either changes, this fails
    @Test
    void theMediumPeakScoresMedium() {
        assertEquals(RiskBand.MEDIUM, bandOf(fallsIllTo(SimulatedPatient.MEDIUM_PEAK).readingAt(FULLY_ILL)));
    }

    @Test
    void theHighPeakScoresHigh() {
        assertEquals(RiskBand.HIGH, bandOf(fallsIllTo(SimulatedPatient.HIGH_PEAK).readingAt(FULLY_ILL)));
    }

    @Test
    void theCriticalPeakScoresCritical() {
        assertEquals(RiskBand.CRITICAL, bandOf(fallsIllTo(SimulatedPatient.CRITICAL_PEAK).readingAt(FULLY_ILL)));
    }

    @Test
    void severityRisesOverTheRampAndThenStaysAtThePeak() {
        SimulatedPatient patient = fallsIllTo(SimulatedPatient.CRITICAL_PEAK);

        assertEquals(0.5, patient.severityAt(ILL_AT.plus(SimulatedPatient.RAMP.dividedBy(2))), 0.001);
        assertEquals(1.0, patient.severityAt(FULLY_ILL), 0.001);
        assertEquals(1.0, patient.severityAt(FULLY_ILL.plusSeconds(3600)), 0.001);
    }

    @Test
    void aCriticalPatientPassesThroughMediumOnTheWay() {             // this is how the simulator found the nurse bug
        SimulatedPatient patient = fallsIllTo(SimulatedPatient.CRITICAL_PEAK);
        boolean sawMedium = false;

        // check every 10 seconds, like the simulator's ticks: readings land at all sorts of points on the ramp
        for (int second = 0; second <= SimulatedPatient.RAMP.toSeconds(); second += 10) {
            if (bandOf(patient.readingAt(ILL_AT.plusSeconds(second))) == RiskBand.MEDIUM) {
                sawMedium = true;
            }
        }

        assertTrue(sawMedium);
    }

    @Test
    void afterTreatmentThePatientIsHealthyAgain() {
        SimulatedPatient patient = fallsIllTo(SimulatedPatient.CRITICAL_PEAK);

        patient.treat();

        assertEquals(0, patient.severityAt(FULLY_ILL));
        assertEquals(RiskBand.LOW, bandOf(patient.readingAt(FULLY_ILL)));
    }

    @Test
    void readingsBelongToThePatientAndCarryTheTime() {
        VitalsReading reading = fallsIllTo(SimulatedPatient.HIGH_PEAK).readingAt(FULLY_ILL);

        assertEquals("p1", reading.patientId());
        assertEquals(FULLY_ILL, reading.timeStamp());
    }
}

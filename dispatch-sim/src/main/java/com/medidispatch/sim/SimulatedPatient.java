package com.medidispatch.sim;

import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.VitalsReading;

import java.time.Duration;
import java.time.Instant;

/**
 * A fake monitored patient whose wearable sends vitals.
 * <p>
 * A patient either stays healthy, or starts deteriorating at a set moment: over {@link #RAMP} their vitals move
 * from healthy towards their peak severity, and stay there until help arrives and {@link #treat()} is called.
 * After treatment they are healthy for the rest of the run.
 * <p>
 * Severity runs from 0 (healthy) to 1 (critical). The three peak levels are tuned so the placeholder
 * {@code SimpleScorer} puts the patient in MEDIUM, HIGH or CRITICAL.
 */
final class SimulatedPatient {

    /** How long it takes to go from healthy to peak severity. */
    static final Duration RAMP = Duration.ofMinutes(10);

    /** Peak severity that ends in the MEDIUM band. */
    static final double MEDIUM_PEAK = 0.53;
    /** Peak severity that ends in the HIGH band. */
    static final double HIGH_PEAK = 0.56;
    /** Peak severity that ends in the CRITICAL band. */
    static final double CRITICAL_PEAK = 1.0;

    private final String id;
    private final Location home;
    private final Instant deterioratesAt;          // null: stays healthy
    private final double peakSeverity;
    private boolean treated;

    /**
     * Creates a patient.
     *
     * @param id             patient id, also used in the engine
     * @param home           where the patient is
     * @param deterioratesAt when they start getting worse, or {@code null} if they stay healthy
     * @param peakSeverity   how bad they get, 0 to 1
     */
    SimulatedPatient(String id, Location home, Instant deterioratesAt, double peakSeverity) {
        this.id = id;
        this.home = home;
        this.deterioratesAt = deterioratesAt;
        this.peakSeverity = peakSeverity;
    }

    /**
     * What the patient's wearable reports at this moment.
     *
     * @param now the time of the reading
     * @return a reading whose values match the patient's current severity
     */
    VitalsReading readingAt(Instant now) {
        double s = severityAt(now);
        return new VitalsReading(id, now,
                between(75, 135, s),                       // heart rate
                between(98, 84, s),                        // SpO2
                between(120, 82, s),                       // systolic BP
                between(80, 50, s),                        // diastolic BP
                between(14, 30, s),                        // respiratory rate
                Math.round((36.8 + (39.2 - 36.8) * s) * 10) / 10.0,   // temperature, one decimal
                s >= 0.85 ? Consciousness.CONFUSED : Consciousness.ALERT);
    }

    /**
     * How ill the patient is right now.
     *
     * @param now the moment to look at
     * @return 0 when healthy or treated, rising to the peak severity over {@link #RAMP}
     */
    double severityAt(Instant now) {
        if (treated || deterioratesAt == null || now.isBefore(deterioratesAt)) {
            return 0;
        }
        double progress = (double) Duration.between(deterioratesAt, now).toSeconds() / RAMP.toSeconds();
        return peakSeverity * Math.min(1.0, progress);
    }

    /** Help has arrived: the patient is healthy from now on. */
    void treat() {
        treated = true;
    }

    String id()                 { return id; }
    Location home()             { return home; }
    boolean willDeteriorate()   { return deterioratesAt != null; }

    // a whole number between the healthy and the sick value, matching the severity
    private static int between(int healthy, int sick, double severity) {
        return (int) Math.round(healthy + (sick - healthy) * severity);
    }
}

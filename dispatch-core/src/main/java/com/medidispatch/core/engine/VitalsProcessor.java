package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.engine.ProcessingResult.Outcome;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.VitalsRepository;
import com.medidispatch.core.scoring.RiskScorer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The single entry point for incoming vitals readings.
 * <p>
 * Every reading goes through the same steps, in this order:
 * <ol>
 *   <li>reject it if the patient is unknown;</li>
 *   <li>reject it if it is timed in the future (beyond {@link #FUTURE_TOLERANCE}) or older than
 *       {@code maxAge};</li>
 *   <li>reject it if it repeats or is older than the patient's newest stored reading;</li>
 *   <li>otherwise store it, score it with the {@link RiskScorer} and apply the assessment through
 *       {@link CaseManager#handle(RiskAssessment)};</li>
 *   <li>if the patient got worse while a unit is on the way, let the {@link ResponseUpgrader} check that the
 *       right kind of unit is coming.</li>
 * </ol>
 * A rejected reading changes nothing: it is not stored, not scored and not applied. Rejections are returned
 * as a {@link ProcessingResult}, not thrown, because they are normal with device data.
 * <p>
 * It never dispatches units; the dispatch loop ({@link DispatchCoordinator}) does that, so there is only
 * ever one place that sends units.
 */
public class VitalsProcessor {

    /** How far ahead of the engine's clock a reading may be timed, to allow for small device clock drift. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final PatientRepository patients;
    private final CaseRepository cases;
    private final VitalsRepository vitals;
    private final RiskScorer scorer;
    private final CaseManager caseManager;
    private final ResponseUpgrader upgrader;
    private final Clock clock;
    private final Duration maxAge;

    /**
     * Creates the processor.
     *
     * @param patients    where registered patients are stored
     * @param cases       where cases are stored (to see how bad the patient was before this reading)
     * @param vitals      where readings are stored
     * @param scorer      turns a reading into a risk assessment
     * @param caseManager applies the assessment to the patient's case
     * @param upgrader    sends a better unit if the patient got worse while help is on the way
     * @param clock       source of the current time
     * @param maxAge      how old a reading may be and still be used; must be positive
     * @throws IllegalArgumentException if an argument is missing or {@code maxAge} is not positive
     */
    public VitalsProcessor(PatientRepository patients,
                           CaseRepository cases,
                           VitalsRepository vitals,
                           RiskScorer scorer,
                           CaseManager caseManager,
                           ResponseUpgrader upgrader,
                           Clock clock,
                           Duration maxAge) {
        if (patients == null || cases == null || vitals == null || scorer == null || caseManager == null
                || upgrader == null || clock == null || maxAge == null) {
            throw new IllegalArgumentException(
                    "patients, cases, vitals, scorer, caseManager, upgrader, clock and maxAge are required");
        }
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive, got " + maxAge);
        }
        this.patients = patients;
        this.cases = cases;
        this.vitals = vitals;
        this.scorer = scorer;
        this.caseManager = caseManager;
        this.upgrader = upgrader;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * Handles one incoming reading.
     * <p>
     * Boundaries count as usable: a reading exactly {@code maxAge} old, or exactly
     * {@link #FUTURE_TOLERANCE} ahead, is accepted.
     *
     * @param reading the reading to handle
     * @return what happened: accepted (with the assessment and the patient's case) or the reason it was ignored
     * @throws IllegalArgumentException if {@code reading} is missing
     */
    public ProcessingResult process(VitalsReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("reading is required");
        }
        // 1. a reading for nobody is ignored
        if (patients.findById(reading.patientId()).isEmpty()) {
            return ProcessingResult.rejected(Outcome.UNKNOWN_PATIENT);
        }

        // 2. it must be about "now": not from the future, not too old
        Instant now = clock.instant();
        Instant takenAt = reading.timeStamp();
        if (takenAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return ProcessingResult.rejected(Outcome.FROM_THE_FUTURE);
        }
        if (takenAt.isBefore(now.minus(maxAge))) {
            return ProcessingResult.rejected(Outcome.TOO_OLD);
        }

        // 3. it must be newer than what we already have, so repeats and late arrivals change nothing
        Optional<VitalsReading> latest = vitals.latestFor(reading.patientId());
        if (latest.isPresent()) {
            Instant latestAt = latest.get().timeStamp();
            if (takenAt.equals(latestAt)) {
                return ProcessingResult.rejected(Outcome.DUPLICATE);
            }
            if (takenAt.isBefore(latestAt)) {
                return ProcessingResult.rejected(Outcome.OUT_OF_ORDER);
            }
        }

        // 4. a fresh reading: store it, score it, apply it to the case
        vitals.save(reading);
        RiskAssessment assessment = scorer.assess(reading);
        Optional<RiskBand> bandBefore = bandOfOpenCase(reading.patientId());
        Optional<Case> currentCase = caseManager.handle(assessment);

        // 5. if the patient got worse while help is on the way, make sure the right unit is coming
        if (currentCase.isPresent() && bandBefore.isPresent()) {
            upgrader.upgradeIfWorse(currentCase.get(), bandBefore.get());
        }
        return ProcessingResult.accepted(assessment, currentCase);
    }

    // the band is copied out now, because handle() changes the same Case object in place
    private Optional<RiskBand> bandOfOpenCase(String patientId) {
        Optional<Case> open = cases.findOpenByPatientId(patientId);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(open.get().band());
    }
}

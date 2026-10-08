package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RiskAssessmentTest {

    private static final List<String> FACTORS = List.of("SpO2 88 below 94 (+2)");

    //accepted
    @Test
    void validAssessmentIsAccepted() {
        assertDoesNotThrow(() ->
                new RiskAssessment("p1", 3, RiskBand.MEDIUM, FACTORS, Instant.now()));
    }

    @Test
    void zeroScoreIsAccepted() {
        assertDoesNotThrow(() ->
                new RiskAssessment("p1", 0, RiskBand.LOW, FACTORS, Instant.now()));
    }

    @Test
    void emptyFactorsListIsAccepted() {
        assertDoesNotThrow(() ->
                new RiskAssessment("p1", 0, RiskBand.LOW, List.of(), Instant.now()));
    }

    //rejected

    @Test
    void nullPatientIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment(null, 3, RiskBand.MEDIUM, FACTORS, Instant.now()));
    }

    @Test
    void blankPatientIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment("  ", 3, RiskBand.MEDIUM, FACTORS, Instant.now()));
    }

    @Test
    void negativeScoreIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment("p1", -1, RiskBand.LOW, FACTORS, Instant.now()));
    }

    @Test
    void nullBandIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment("p1", 3, null, FACTORS, Instant.now()));
    }

    @Test
    void nullFactorsIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment("p1", 3, RiskBand.MEDIUM, null, Instant.now()));
    }

    @Test
    void nullComputedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskAssessment("p1", 3, RiskBand.MEDIUM, FACTORS, null));
    }
    //immutability
    @Test
    void changingTheOriginalListDoesNotAffectTheAssessment() {
        List<String> original = new ArrayList<>(List.of("a"));
        RiskAssessment assessment =
                new RiskAssessment("p1", 1, RiskBand.LOW, original, Instant.now());

        original.add("b");                                // caller edits their own list

        assertEquals(1, assessment.factors().size());     // the record kept its snapshot
    }

    @Test
    void factorsListCannotBeModifiedFromOutside() {
        RiskAssessment assessment =
                new RiskAssessment("p1", 1, RiskBand.LOW, FACTORS, Instant.now());

        assertThrows(UnsupportedOperationException.class,
                () -> assessment.factors().add("x"));
    }
}
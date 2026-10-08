package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class VitalsReadingTest {
    private VitalsReading reading(Integer hr, Integer spo2, Integer sys, Integer dia, Integer rr, Double temp) {
        return new VitalsReading("p1", Instant.now(), hr, spo2, sys, dia, rr, temp, Consciousness.ALERT);
    }

    private VitalsReading valid() {
        return reading(80, 96, 120, 80, 16, 36.8);
    }

    //accepted
    @Test
    void validReadingIsAccepted() {
        assertDoesNotThrow(this::valid);
    }

    @Test
    void allOptionalNumbersMissingIsAccepted() {
        assertDoesNotThrow(() -> reading(null, null, null, null, null, null));
    }

    @Test
    void diastolicWithoutSystolicIsAccepted() {          // the null-crash bug
        assertDoesNotThrow(() -> reading(80, 96, null, 80, 16, 36.8));
    }

    @Test
    void systolicWithoutDiastolicIsAccepted() {
        assertDoesNotThrow(() -> reading(80, 96, 120, null, 16, 36.8));
    }

    @Test
    void diastolicEqualToSystolicIsAccepted() {
        assertDoesNotThrow(() -> reading(80, 96, 100, 100, 16, 36.8));
    }

    @Test
    void dangerousButRealValuesAreAccepted() {            // validation is not scoring
        assertDoesNotThrow(() -> reading(30, 70, 60, 40, 5, 28.0));
    }

    @Test
    void boundaryValuesAreAccepted() {
        assertDoesNotThrow(() -> reading(0, 0, 30, 10, 0, 25.0));
        assertDoesNotThrow(() -> reading(300, 100, 300, 200, 45, 45.0));
    }

    // ---------- rejected: out of range ----------

    @Test
    void spo2OutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 101, 120, 80, 16, 36.8));
        assertThrows(IllegalArgumentException.class, () -> reading(80, -1, 120, 80, 16, 36.8));
    }

    @Test
    void heartRateOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(301, 96, 120, 80, 16, 36.8));
        assertThrows(IllegalArgumentException.class, () -> reading(-1, 96, 120, 80, 16, 36.8));
    }

    @Test
    void systolicOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 301, 80, 16, 36.8));
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 29, null, 16, 36.8));
    }

    @Test
    void diastolicOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 201, 16, 36.8));
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 9, 16, 36.8));
    }

    @Test
    void diastolicHigherThanSystolicIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 100, 110, 16, 36.8));
    }

    @Test
    void respRateOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 80, 46, 36.8));
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 80, -1, 36.8));
    }

    @Test
    void temperatureOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 80, 16, 24.9));
        assertThrows(IllegalArgumentException.class, () -> reading(80, 96, 120, 80, 16, 45.1));
    }

    //rejected: required fields

    @Test
    void nullPatientIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new VitalsReading(null, Instant.now(), 80, 96, 120, 80, 16, 36.8, Consciousness.ALERT));
    }

    @Test
    void blankPatientIdIsRejected() {                     
        assertThrows(IllegalArgumentException.class, () ->
                new VitalsReading("  ", Instant.now(), 80, 96, 120, 80, 16, 36.8, Consciousness.ALERT));
    }

    @Test
    void nullTimestampIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new VitalsReading("p1", null, 80, 96, 120, 80, 16, 36.8, Consciousness.ALERT));
    }

    @Test
    void nullConsciousnessIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new VitalsReading("p1", Instant.now(), 80, 96, 120, 80, 16, 36.8, null));
    }
}
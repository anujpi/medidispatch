package com.medidispatch.core.response;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LadderResponseDeciderTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Patient PATIENT = new Patient("p1", new Location(0, 0));

    private final LadderResponseDecider decider = new LadderResponseDecider();

    private ResponsePlan planFor(RiskBand band) {
        return decider.decide(new Case("c1", "p1", band, NOW), PATIENT);
    }

    @Test
    void criticalGetsOnlyAnAdvancedAmbulance() {
        assertEquals(List.of(ResourceType.AMBULANCE_ALS), planFor(RiskBand.CRITICAL).primaryTypes());
    }

    @Test
    void highTriesABasicAmbulanceBeforeAnAdvancedOne() {
        assertEquals(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS),
                planFor(RiskBand.HIGH).primaryTypes());
    }

    @Test
    void mediumGetsANurse() {
        assertEquals(List.of(ResourceType.NURSE), planFor(RiskBand.MEDIUM).primaryTypes());
    }

    @Test
    void lowSendsNobody() {
        assertFalse(planFor(RiskBand.LOW).needsUnit());
    }

    @Test
    void everyBandThatOpensACaseNeedsAUnit() {
        for (RiskBand band : RiskBand.values()) {
            if (band.compareTo(RiskBand.MEDIUM) >= 0) {
                assertTrue(planFor(band).needsUnit(), "no unit for " + band);
            }
        }
    }

    @Test
    void aMissingCaseOrPatientIsRejected() {
        Case c = new Case("c1", "p1", RiskBand.HIGH, NOW);

        assertThrows(IllegalArgumentException.class, () -> decider.decide(null, PATIENT));
        assertThrows(IllegalArgumentException.class, () -> decider.decide(c, null));
    }

    @Test
    void criticalAlsoAsksForADoctorThenANurseAsFirstResponder() {
        assertEquals(List.of(ResourceType.DOCTOR, ResourceType.NURSE), planFor(RiskBand.CRITICAL).supportTypes());
    }

    @Test
    void noOtherBandAsksForAFirstResponder() {
        assertFalse(planFor(RiskBand.HIGH).wantsSupport());
        assertFalse(planFor(RiskBand.MEDIUM).wantsSupport());
        assertFalse(planFor(RiskBand.LOW).wantsSupport());
    }
}

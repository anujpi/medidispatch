package com.medidispatch.core.engine;

import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ResponseTypesTest {

    @Test
    void criticalNeedsAnAdvancedAmbulance() {
        assertEquals(Set.of(ResourceType.AMBULANCE_ALS), ResponseTypes.forBand(RiskBand.CRITICAL));
    }

    @Test
    void highCanUseEitherKindOfAmbulance() {
        assertEquals(Set.of(ResourceType.AMBULANCE_ALS, ResourceType.AMBULANCE_BLS),
                ResponseTypes.forBand(RiskBand.HIGH));
    }

    @Test
    void mediumAndLowGetANurse() {
        assertEquals(Set.of(ResourceType.NURSE), ResponseTypes.forBand(RiskBand.MEDIUM));
        assertEquals(Set.of(ResourceType.NURSE), ResponseTypes.forBand(RiskBand.LOW));
    }

    @Test
    void everyBandHasAtLeastOneAllowedType() {
        for (RiskBand band : RiskBand.values()) {
            assertFalse(ResponseTypes.forBand(band).isEmpty(), "no unit type for " + band);
        }
    }
}

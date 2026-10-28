package com.medidispatch.core.response;

import com.medidispatch.core.domain.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ResponsePlanTest {

    @Test
    void keepsTheUnitTypesInTheOrderGiven() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS));

        assertEquals(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS), plan.unitTypes());
    }

    @Test
    void aPlanWithUnitTypesNeedsAUnitAndAnEmptyOneDoesNot() {
        assertTrue(new ResponsePlan(List.of(ResourceType.NURSE)).needsUnit());
        assertFalse(new ResponsePlan(List.of()).needsUnit());
    }

    @Test
    void allowedTypesHasTheSameTypesWithoutTheOrder() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS));

        assertEquals(Set.of(ResourceType.AMBULANCE_ALS, ResourceType.AMBULANCE_BLS), plan.allowedTypes());
    }

    @Test
    void changingTheOriginalListDoesNotChangeThePlan() {
        List<ResourceType> types = new ArrayList<>(List.of(ResourceType.NURSE));
        ResponsePlan plan = new ResponsePlan(types);

        types.add(ResourceType.AMBULANCE_ALS);

        assertEquals(List.of(ResourceType.NURSE), plan.unitTypes());
    }

    @Test
    void thePlanCannotBeChangedFromOutside() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.NURSE));

        assertThrows(UnsupportedOperationException.class, () -> plan.unitTypes().add(ResourceType.COURIER));
    }

    @Test
    void aMissingListOrAMissingEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResponsePlan(null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponsePlan(Arrays.asList(ResourceType.NURSE, null)));   // List.of cannot hold null
    }

    @Test
    void theSameTypeTwiceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResponsePlan(List.of(ResourceType.NURSE, ResourceType.NURSE)));
    }
}

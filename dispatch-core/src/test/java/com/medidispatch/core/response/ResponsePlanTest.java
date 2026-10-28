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

        assertEquals(List.of(ResourceType.AMBULANCE_BLS, ResourceType.AMBULANCE_ALS), plan.primaryTypes());
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

        assertEquals(List.of(ResourceType.NURSE), plan.primaryTypes());
    }

    @Test
    void thePlanCannotBeChangedFromOutside() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.NURSE));

        assertThrows(UnsupportedOperationException.class, () -> plan.primaryTypes().add(ResourceType.COURIER));
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

    // ---------- first responders ----------

    @Test
    void aPlanCanAlsoListFirstResponderTypesInOrder() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.AMBULANCE_ALS),
                List.of(ResourceType.DOCTOR, ResourceType.NURSE));

        assertEquals(List.of(ResourceType.AMBULANCE_ALS), plan.primaryTypes());
        assertEquals(List.of(ResourceType.DOCTOR, ResourceType.NURSE), plan.supportTypes());
        assertTrue(plan.wantsSupport());
    }

    @Test
    void aPrimaryOnlyPlanWantsNoFirstResponder() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.NURSE));

        assertTrue(plan.supportTypes().isEmpty());
        assertFalse(plan.wantsSupport());
    }

    @Test
    void allowedTypesOnlyCoverTheMainResponse() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.AMBULANCE_ALS), List.of(ResourceType.NURSE));

        assertEquals(Set.of(ResourceType.AMBULANCE_ALS), plan.allowedTypes());
    }

    @Test
    void aTypeCannotBeBothMainResponseAndFirstResponder() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResponsePlan(List.of(ResourceType.NURSE), List.of(ResourceType.NURSE)));
    }

    @Test
    void theFirstResponderListIsCheckedLikeTheMainList() {
        List<ResourceType> als = List.of(ResourceType.AMBULANCE_ALS);

        assertThrows(IllegalArgumentException.class, () -> new ResponsePlan(als, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponsePlan(als, Arrays.asList(ResourceType.NURSE, null)));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponsePlan(als, List.of(ResourceType.NURSE, ResourceType.NURSE)));
    }

    @Test
    void theFirstResponderListCannotBeChangedFromOutside() {
        ResponsePlan plan = new ResponsePlan(List.of(ResourceType.AMBULANCE_ALS), List.of(ResourceType.NURSE));

        assertThrows(UnsupportedOperationException.class, () -> plan.supportTypes().add(ResourceType.DOCTOR));
    }
}

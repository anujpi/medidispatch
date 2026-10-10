package com.medidispatch.core.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.medidispatch.core.domain.ResourceStatus.*;
import static org.junit.jupiter.api.Assertions.*;

public class ResourceStatusTest {

    private static final Map<ResourceStatus, Set<ResourceStatus>> ALLOWED = Map.of(
            AVAILABLE, EnumSet.of(ASSIGNED, OFFLINE),
            ASSIGNED,  EnumSet.of(AVAILABLE, OFFLINE),
            OFFLINE,   EnumSet.of(AVAILABLE)
    );

    @Test
    void everyPairOfStatusesMatchesTheTable() {
        for (ResourceStatus from : ResourceStatus.values()) {
            for (ResourceStatus to : ResourceStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                assertEquals(expected, from.canTransitionTo(to),
                        from + " -> " + to + " should be " + (expected ? "allowed" : "refused"));
            }
        }
    }

    @Test
    void assignedCannotBeAssignedAgain() {            // the double-assignment rule
        assertFalse(ASSIGNED.canTransitionTo(ASSIGNED));
    }

    @Test
    void offlineMustComeBackThroughAvailable() {
        assertFalse(OFFLINE.canTransitionTo(ASSIGNED));
        assertTrue(OFFLINE.canTransitionTo(AVAILABLE));
    }
}
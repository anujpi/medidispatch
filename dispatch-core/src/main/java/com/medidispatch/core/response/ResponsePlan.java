package com.medidispatch.core.response;

import com.medidispatch.core.domain.ResourceType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a case gets: the unit types to try, most preferred first. An empty plan means no unit is sent
 * (for example, a self-care prompt).
 *
 * @param unitTypes the unit types in order of preference; no nulls, no duplicates; stored as an immutable copy
 */
public record ResponsePlan(List<ResourceType> unitTypes) {

    /**
     * Validates the plan and makes it immutable.
     *
     * @throws IllegalArgumentException if the list is missing, contains null, or lists a type twice
     */
    public ResponsePlan{
        if(unitTypes == null){
            throw new IllegalArgumentException("Unit Types is required");
        }
        Set<ResourceType> seen = new HashSet<>();
        for (ResourceType type : unitTypes) {
            if (type == null) {
                throw new IllegalArgumentException("unitTypes cannot contain null");
            }
            if (!seen.add(type)) {
                throw new IllegalArgumentException("unitTypes lists " + type + " twice");
            }
        }
        unitTypes = List.copyOf(unitTypes);
    }
    /**
     * Says whether any unit should be sent.
     *
     * @return {@code true} if the plan lists at least one unit type
     */
    public boolean needsUnit() {
        return !unitTypes.isEmpty();
    }
    /**
     * The same types without the order, for callers that only ask "is this type allowed?".
     *
     * @return the allowed unit types
     */
    public Set<ResourceType> allowedTypes() {
        return Set.copyOf(unitTypes);
    }
}

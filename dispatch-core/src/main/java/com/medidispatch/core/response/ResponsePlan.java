package com.medidispatch.core.response;

import com.medidispatch.core.domain.ResourceType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a case gets.
 * <ul>
 *   <li><b>Primary</b>: the unit types to try for the main response, most preferred first. Empty means no
 *       unit is sent (for example, a self-care prompt).</li>
 *   <li><b>Support</b>: first-responder types, most preferred first, sent alongside the primary when one is
 *       closer. Empty means no first responder.</li>
 * </ul>
 *
 * @param primaryTypes main-response unit types in order of preference; no nulls, no duplicates
 * @param supportTypes first-responder unit types in order of preference; no nulls, no duplicates,
 *                     and none that is also a primary type
 */
public record ResponsePlan(List<ResourceType> primaryTypes, List<ResourceType> supportTypes) {

    /**
     * Validates the plan and makes both lists immutable.
     *
     * @throws IllegalArgumentException if a list is missing, contains null or a duplicate,
     *                                  or a type is in both lists
     */
    public ResponsePlan{
        checkTypes(primaryTypes, "primaryTypes");
        checkTypes(supportTypes, "supportTypes");
        for (ResourceType type : supportTypes) {
            if (primaryTypes.contains(type)) {
                throw new IllegalArgumentException(type + " cannot be both a primary and a support type");
            }
        }
        primaryTypes = List.copyOf(primaryTypes);
        supportTypes = List.copyOf(supportTypes);
    }

    /**
     * A plan with a main response only, no first responder.
     *
     * @param primaryTypes main-response unit types in order of preference
     */
    public ResponsePlan(List<ResourceType> primaryTypes) {
        this(primaryTypes, List.of());
    }

    /**
     * Says whether a main-response unit should be sent.
     *
     * @return {@code true} if the plan lists at least one primary type
     */
    public boolean needsUnit() {
        return !primaryTypes.isEmpty();
    }

    /**
     * Says whether a first responder should be sent alongside the main response.
     *
     * @return {@code true} if the plan lists at least one support type
     */
    public boolean wantsSupport() {
        return !supportTypes.isEmpty();
    }

    /**
     * The primary types without the order, for callers that only ask "may this unit serve the case?".
     *
     * @return the allowed main-response unit types
     */
    public Set<ResourceType> allowedTypes() {
        return Set.copyOf(primaryTypes);
    }

    private static void checkTypes(List<ResourceType> types, String name) {
        if (types == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        Set<ResourceType> seen = new HashSet<>();
        for (ResourceType type : types) {
            if (type == null) {
                throw new IllegalArgumentException(name + " cannot contain null");
            }
            if (!seen.add(type)) {
                throw new IllegalArgumentException(name + " lists " + type + " twice");
            }
        }
    }
}

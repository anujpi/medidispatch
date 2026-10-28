package com.medidispatch.core.port;

import java.util.Optional;

/**
 * Port: an atomic "this unit is mine" lock, so two dispatchers can never send the same unit to two cases.
 * <p>
 * {@link #tryClaim(String, String)} must be atomic: when several callers race for one unit, exactly one
 * wins. The in-memory adapter uses {@code ConcurrentHashMap.putIfAbsent}; Redis {@code SET NX} will do the
 * same job later.
 */
public interface ResourceClaimer {

    /**
     * Tries to claim a unit for a case.
     *
     * @param resourceId the unit to claim
     * @param caseId     the case that wants it
     * @return {@code true} if this call won the claim, {@code false} if the unit is already claimed
     */
    boolean tryClaim(String resourceId,String caseId);

    /**
     * Releases a claim, but only if it is held by the given case, so one case can never free another's unit.
     *
     * @param resourceId the claimed unit
     * @param caseId     the case that should hold the claim
     * @return {@code true} if the claim was held by that case and is now released
     */
    boolean release(String resourceId, String caseId);

    /**
     * Says which case holds a unit's claim.
     *
     * @param resourceId the unit
     * @return the id of the case holding the claim, or empty if the unit is unclaimed
     */
    Optional<String> claimedBy(String resourceId);
}

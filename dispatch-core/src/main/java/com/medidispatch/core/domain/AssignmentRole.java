package com.medidispatch.core.domain;

/**
 * Why a unit is on a case.
 * <ul>
 *   <li>{@link #PRIMARY}: the unit the case is waiting for (usually an ambulance). Losing it puts the case
 *       back in the queue.</li>
 *   <li>{@link #SUPPORT}: a first responder (nurse or doctor) sent to start care before the primary arrives.
 *       Best-effort: losing it does not requeue the case, and it stands down when the primary arrives.</li>
 * </ul>
 */
public enum AssignmentRole {
    PRIMARY, SUPPORT
}

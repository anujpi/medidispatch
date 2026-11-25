package com.medidispatch.core.event;

/**
 * What a dispatcher overrode in the engine's decisions.
 * <ul>
 *   <li>{@code CANCEL_CASE}: the case was called off (a false alarm, the patient is fine); its units were freed.</li>
 *   <li>{@code SEND_UNIT}: the dispatcher chose the unit to send, replacing the engine's choice if there was one.</li>
 *   <li>{@code OUT_OF_SERVICE}: a unit was taken off duty (breakdown, crew unwell); its trip, if any, failed.</li>
 * </ul>
 */
public enum OverrideKind {
    CANCEL_CASE, SEND_UNIT, OUT_OF_SERVICE
}

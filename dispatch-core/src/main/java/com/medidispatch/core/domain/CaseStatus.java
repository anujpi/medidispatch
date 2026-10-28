package com.medidispatch.core.domain;

/**
 * Lifecycle of a {@link Case}.
 * <ul>
 *   <li>{@code MONITORING}: the patient is watched; no help is needed right now.</li>
 *   <li>{@code ALERTED}: help is needed; the case waits in the queue for a unit.</li>
 *   <li>{@code RESPONDING}: a unit has been assigned.</li>
 *   <li>{@code STABILISED}: the patient has been stabilised on scene.</li>
 *   <li>{@code CLOSED} / {@code CANCELLED}: finished; the case never changes again.</li>
 * </ul>
 */
public enum CaseStatus {
    MONITORING, ALERTED, RESPONDING, STABILISED, CLOSED, CANCELLED;

    /**
     * Says whether this status may move to {@code next}.
     * <p>
     * Allowed moves: MONITORING to ALERTED or CANCELLED; ALERTED to RESPONDING, MONITORING (recovered)
     * or CANCELLED; RESPONDING to STABILISED, ALERTED (the unit was lost) or CANCELLED; STABILISED to
     * CLOSED or ALERTED (worse again). CLOSED and CANCELLED never move.
     *
     * @param next the status to move to
     * @return {@code true} if the move is allowed
     */
    public boolean canTransitionTo(CaseStatus next) {
        return switch (this) {
            case MONITORING -> next == ALERTED || next == CANCELLED;
            case ALERTED    -> next == RESPONDING || next == MONITORING || next == CANCELLED;
            case RESPONDING -> next == STABILISED || next == ALERTED || next == CANCELLED;
            case STABILISED -> next == CLOSED || next == ALERTED;
            case CLOSED, CANCELLED -> false;
        };
    }

    /**
     * Says whether the case is finished for good.
     *
     * @return {@code true} for {@code CLOSED} and {@code CANCELLED}
     */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }
}
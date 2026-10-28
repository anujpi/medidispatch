package com.medidispatch.core.domain;

/**
 * Lifecycle of an {@link Assignment}, one unit's trip to one case.
 * <ul>
 *   <li>{@code ASSIGNED}: the unit was told to go and has not acknowledged yet.</li>
 *   <li>{@code EN_ROUTE}: the unit is on its way.</li>
 *   <li>{@code ARRIVED}: the crew is with the patient.</li>
 *   <li>{@code DONE}: finished normally.</li>
 *   <li>{@code CANCELLED}: called off, for example because the unit was preempted for a worse case.</li>
 *   <li>{@code TIMED_OUT}: the unit never acknowledged.</li>
 *   <li>{@code FAILED}: the unit could not complete (breakdown, lost signal, stalled).</li>
 * </ul>
 * The last four are terminal. Keeping them separate records <em>why</em> a trip ended.
 */
public enum AssignmentStatus {
    ASSIGNED,EN_ROUTE,ARRIVED,DONE,CANCELLED,TIMED_OUT,FAILED;

    /**
     * Says whether this status may move to {@code next}.
     * <p>
     * Only a trip that is still {@code ASSIGNED} can time out. A trip can be cancelled or fail at any
     * stage before it ends. Terminal statuses never move.
     *
     * @param next the status to move to
     * @return {@code true} if the move is allowed
     */
    public boolean canTransitionTo(AssignmentStatus next){
        return switch(this){
            case ASSIGNED -> next == EN_ROUTE || next == CANCELLED || next == TIMED_OUT || next == FAILED;
            case EN_ROUTE -> next == ARRIVED || next == CANCELLED || next == FAILED;
            case ARRIVED -> next == DONE || next == CANCELLED || next == FAILED;
            case DONE, CANCELLED,TIMED_OUT, FAILED -> false;
        };
    }
    /**
     * Says whether the trip has ended.
     *
     * @return {@code true} for {@code DONE}, {@code CANCELLED}, {@code TIMED_OUT} and {@code FAILED}
     */
    public boolean isTerminal(){
        return this == DONE || this == CANCELLED || this == TIMED_OUT || this == FAILED;
    }
}

package com.medidispatch.core.domain;

/**
 * Availability of a {@link Resource}.
 * <ul>
 *   <li>{@code AVAILABLE}: free to be sent.</li>
 *   <li>{@code ASSIGNED}: on a trip.</li>
 *   <li>{@code OFFLINE}: out of service (broke down, lost signal, never answered, off shift).</li>
 * </ul>
 */
public enum ResourceStatus {
    AVAILABLE, ASSIGNED, OFFLINE;

    /**
     * Says whether this status may move to {@code next}.
     * <p>
     * An offline unit can only come back as {@code AVAILABLE}, never straight to {@code ASSIGNED},
     * so it has to be declared fit before it is sent again.
     *
     * @param next the status to move to
     * @return {@code true} if the move is allowed
     */
    public boolean canTransitionTo(ResourceStatus next) {
        return switch (this) {
            case AVAILABLE -> next == ASSIGNED || next == OFFLINE;
            case ASSIGNED -> next == AVAILABLE || next == OFFLINE;
            case OFFLINE -> next == AVAILABLE;
        };
    }
}

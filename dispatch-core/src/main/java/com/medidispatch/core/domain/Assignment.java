package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One unit's trip to one case: who was sent, to which case, when, and how the trip is going.
 * <p>
 * Gets a random UUID as its id and starts in {@link AssignmentStatus#ASSIGNED}. Status changes go through
 * {@link #transitionTo(AssignmentStatus)}, which refuses illegal moves.
 */
public class Assignment {

    private final String id;
    private final String caseId;
    private final String resourceId;
    private final Instant assignedAt;
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

    /**
     * Creates a new trip in {@link AssignmentStatus#ASSIGNED}.
     *
     * @param caseId     the case the unit is sent to; never blank
     * @param resourceId the unit that is sent; never blank
     * @param assignedAt when the unit was assigned; the acknowledgement deadline is counted from here
     * @throws IllegalArgumentException if an id is blank or any argument is missing
     */
    public Assignment(String caseId, String resourceId, Instant assignedAt) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (assignedAt == null) {
            throw new IllegalArgumentException("assignedAt is required");
        }
        this.id = UUID.randomUUID().toString();
        this.caseId = caseId;
        this.resourceId = resourceId;
        this.assignedAt = assignedAt;
    }

    /**
     * Moves the trip to a new status, if the move is allowed.
     *
     * @param next the status to move to
     * @throws IllegalArgumentException if {@code next} is missing
     * @throws IllegalStateException    if the move is not allowed; the status is left unchanged
     * @see AssignmentStatus#canTransitionTo(AssignmentStatus)
     */
    public void transitionTo(AssignmentStatus next) {
        if (next == null) {
            throw new IllegalArgumentException("next status is required");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot go from " + status + " to " + next);
        }
        status = next;
    }

    public String id()                { return id; }
    public String caseId()            { return caseId; }
    public String resourceId()        { return resourceId; }
    public Instant assignedAt()       { return assignedAt; }
    public AssignmentStatus status()  { return status; }
}
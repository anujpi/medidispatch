package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One unit's trip to one case: who was sent, to which case, in which role, when, and how the trip is going.
 * <p>
 * Gets a random UUID as its id and starts in {@link AssignmentStatus#ASSIGNED}. Status changes go through
 * {@link #transitionTo(AssignmentStatus)}, which refuses illegal moves. A case can have one
 * {@link AssignmentRole#PRIMARY} trip and, alongside it, a {@link AssignmentRole#SUPPORT} trip. A PRIMARY trip can
 * become SUPPORT once, when a more capable unit is needed ({@link #demoteToSupport()}).
 */
public class Assignment {

    private final String id;
    private final String caseId;
    private final String resourceId;
    private AssignmentRole role;
    private final Instant assignedAt;
    private AssignmentStatus status;

    /**
     * Creates a new trip in {@link AssignmentStatus#ASSIGNED}, with a new random id.
     *
     * @param caseId     the case the unit is sent to; never blank
     * @param resourceId the unit that is sent; never blank
     * @param role       why the unit is sent: the main response or a first responder
     * @param assignedAt when the unit was assigned; the acknowledgement deadline is counted from here
     * @throws IllegalArgumentException if an id is blank or any argument is missing
     */
    public Assignment(String caseId, String resourceId, AssignmentRole role, Instant assignedAt) {
        this(UUID.randomUUID().toString(), caseId, resourceId, role, AssignmentStatus.ASSIGNED, assignedAt);
    }

    /**
     * Rebuilds a trip that already exists (read back from storage), with its saved id, role and status.
     * <p>
     * For storage adapters only. The public constructor invents a new id and starts in ASSIGNED, and
     * {@link #transitionTo(AssignmentStatus)} only allows legal moves, so neither can rebuild a stored trip.
     * From then on the restored trip obeys every rule as usual.
     *
     * @param id         the saved trip id; never blank
     * @param caseId     the case the unit was sent to; never blank
     * @param resourceId the unit that was sent; never blank
     * @param role       its saved role
     * @param status     its saved status
     * @param assignedAt when the unit was assigned
     * @return the rebuilt trip
     * @throws IllegalArgumentException if an id is blank or any argument is missing
     */
    public static Assignment restore(String id, String caseId, String resourceId, AssignmentRole role,
                                     AssignmentStatus status, Instant assignedAt) {
        return new Assignment(id, caseId, resourceId, role, status, assignedAt);
    }

    // Both ways in (new trip, restored trip) end here, so the checks live in one place.
    private Assignment(String id, String caseId, String resourceId, AssignmentRole role,
                       AssignmentStatus status, Instant assignedAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (assignedAt == null) {
            throw new IllegalArgumentException("assignedAt is required");
        }
        this.id = id;
        this.caseId = caseId;
        this.resourceId = resourceId;
        this.role = role;
        this.status = status;
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

    /**
     * Turns the main response into a first responder, because a more capable unit is needed.
     * <p>
     * The trip keeps going; only its role changes, once, from PRIMARY to SUPPORT.
     *
     * @throws IllegalStateException if the trip is already SUPPORT or has ended; nothing is changed
     */
    public void demoteToSupport() {
        if (role != AssignmentRole.PRIMARY) {
            throw new IllegalStateException("Trip " + id + " is already " + role);
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("Trip " + id + " has already ended (" + status + ")");
        }
        role = AssignmentRole.SUPPORT;
    }

    public String id()                { return id; }
    public String caseId()            { return caseId; }
    public String resourceId()        { return resourceId; }
    public AssignmentRole role()      { return role; }
    public Instant assignedAt()       { return assignedAt; }
    public AssignmentStatus status()  { return status; }
}
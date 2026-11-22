package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One unit's trip to one case: who was sent, to which case, in which role, when, and how the trip is going.
 * <p>
 * Gets a random UUID as its id and starts in {@link AssignmentStatus#ASSIGNED}. Status changes go through
 * {@link #transitionTo(AssignmentStatus)}, which refuses illegal moves. A case can have one
 * {@link AssignmentRole#PRIMARY} trip and, alongside it, a {@link AssignmentRole#SUPPORT} trip. A PRIMARY trip can
 * become SUPPORT once, when a more capable unit is needed ({@link #demoteToSupport()}).
 * <p>
 * The main trip can go on to carry the patient to a hospital: {@link #startTransport(String)} is the only way into
 * {@link AssignmentStatus#TRANSPORTING}, so a transporting trip always knows its hospital.
 */
public class Assignment {

    private final String id;
    private final String caseId;
    private final String resourceId;
    private AssignmentRole role;
    private final Instant assignedAt;
    private AssignmentStatus status;
    private String hospitalId;

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
        this(UUID.randomUUID().toString(), caseId, resourceId, role, AssignmentStatus.ASSIGNED, assignedAt, null);
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
     * @param hospitalId the hospital the patient is (or was) taken to; {@code null} if there was no transport
     * @return the rebuilt trip
     * @throws IllegalArgumentException if an id is blank, a required argument is missing, or a TRANSPORTING trip
     *                                  has no hospital
     */
    public static Assignment restore(String id, String caseId, String resourceId, AssignmentRole role,
                                     AssignmentStatus status, Instant assignedAt, String hospitalId) {
        return new Assignment(id, caseId, resourceId, role, status, assignedAt, hospitalId);
    }

    // Both ways in (new trip, restored trip) end here, so the checks live in one place.
    private Assignment(String id, String caseId, String resourceId, AssignmentRole role,
                       AssignmentStatus status, Instant assignedAt, String hospitalId) {
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
        if (hospitalId != null && hospitalId.isBlank()) {
            throw new IllegalArgumentException("hospitalId cannot be blank");
        }
        // A damaged stored row fails here, when it is read, not later on the hospital screen.
        if (status == AssignmentStatus.TRANSPORTING && hospitalId == null) {
            throw new IllegalArgumentException("a TRANSPORTING trip needs a hospital");
        }
        this.id = id;
        this.caseId = caseId;
        this.resourceId = resourceId;
        this.role = role;
        this.status = status;
        this.assignedAt = assignedAt;
        this.hospitalId = hospitalId;
    }

    /**
     * Moves the trip to a new status, if the move is allowed.
     *
     * @param next the status to move to
     * @throws IllegalArgumentException if {@code next} is missing
     * @throws IllegalStateException    if the move is not allowed, or {@code next} is TRANSPORTING (use
     *                                  {@link #startTransport(String)}); the status is left unchanged
     * @see AssignmentStatus#canTransitionTo(AssignmentStatus)
     */
    public void transitionTo(AssignmentStatus next) {
        if (next == null) {
            throw new IllegalArgumentException("next status is required");
        }
        if (next == AssignmentStatus.TRANSPORTING) {
            throw new IllegalStateException("Use startTransport: a transport needs a hospital");
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
     * @throws IllegalStateException if the trip is already SUPPORT, has ended, or is carrying the patient; nothing
     *                               is changed
     */
    public void demoteToSupport() {
        if (role != AssignmentRole.PRIMARY) {
            throw new IllegalStateException("Trip " + id + " is already " + role);
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("Trip " + id + " has already ended (" + status + ")");
        }
        if (status == AssignmentStatus.TRANSPORTING) {
            throw new IllegalStateException("Trip " + id + " is carrying the patient");
        }
        role = AssignmentRole.SUPPORT;
    }

    /**
     * The crew sets off for a hospital with the patient: ARRIVED to TRANSPORTING, and the trip records where to.
     *
     * @param hospitalId the hospital the patient is taken to; never blank
     * @throws IllegalArgumentException if {@code hospitalId} is blank
     * @throws IllegalStateException    if this is not the main (PRIMARY) trip or it is not ARRIVED; nothing is
     *                                  changed
     */
    public void startTransport(String hospitalId) {
        if (hospitalId == null || hospitalId.isBlank()) {
            throw new IllegalArgumentException("hospitalId is required");
        }
        if (role != AssignmentRole.PRIMARY) {
            throw new IllegalStateException("Only the main unit transports; trip " + id + " is " + role);
        }
        if (!status.canTransitionTo(AssignmentStatus.TRANSPORTING)) {
            throw new IllegalStateException("Cannot go from " + status + " to TRANSPORTING");
        }
        status = AssignmentStatus.TRANSPORTING;
        this.hospitalId = hospitalId;
    }

    public String id()                { return id; }
    public String caseId()            { return caseId; }
    public String resourceId()        { return resourceId; }
    public AssignmentRole role()      { return role; }
    public Instant assignedAt()       { return assignedAt; }
    public AssignmentStatus status()  { return status; }

    /** The hospital the patient is (or was) taken to; empty if the trip never went to TRANSPORTING. */
    public Optional<String> hospitalId() { return Optional.ofNullable(hospitalId); }
}
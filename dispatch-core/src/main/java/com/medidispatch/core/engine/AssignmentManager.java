package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.event.AssignmentRoleChanged;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;
import java.util.Set;

/**
 * Moves a trip through its lifecycle and cleans up when it ends.
 * <p>
 * Every method finds the trip by its own id, moves it to the next status (illegal moves throw before
 * anything changes), saves it and publishes an {@link AssignmentStatusChanged}. When the trip ends, the
 * unit's claim is released and the unit is returned: to {@code AVAILABLE} after a normal end or a
 * cancellation, to {@code OFFLINE} after a timeout or failure, so a broken or silent unit is never picked
 * again straight away.
 * <p>
 * Trips are addressed by assignment id, not case id, because a unit reports about its own trip and a case
 * can have more than one trip at a time (an ambulance and a first responder). Every method returns the
 * updated trip, so callers know which case and role it belonged to.
 */
public class AssignmentManager {
    private final AssignmentRepository assignments;
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    private final EventPublisher publisher;
    private final Clock clock;

    /**
     * Creates the manager.
     *
     * @param assignments where trips are stored
     * @param resources   where units are stored
     * @param claimer     holds the unit claims to release when a trip ends
     * @param publisher   where status changes are announced
     * @param clock       source of event times
     * @throws IllegalArgumentException if any argument is missing
     */
    public AssignmentManager(AssignmentRepository assignments, ResourceRepository resources,
                             ResourceClaimer claimer, EventPublisher publisher, Clock clock) {
        if (assignments == null || resources == null || claimer == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("assignments, resources, claimer, publisher and clock are required");
        }
        this.assignments = assignments;
        this.resources = resources;
        this.claimer = claimer;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * The crew acknowledged and set off: ASSIGNED to EN_ROUTE.
     *
     * @param assignmentId the trip that moves
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or the move is not allowed
     */
    public Assignment onEnRoute(String assignmentId) {
        return move(assignmentId, AssignmentStatus.EN_ROUTE);
    }

    /**
     * The crew reached the patient: EN_ROUTE to ARRIVED.
     * <p>
     * When the main response (PRIMARY) arrives, the case's first responder stands down and is freed for the
     * next call: its trip becomes DONE if the responder had arrived, or CANCELLED if they were still on the
     * way.
     *
     * @param assignmentId the trip that moves
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or the move is not allowed
     */
    public Assignment onArrived(String assignmentId) {
        Assignment arrived = move(assignmentId, AssignmentStatus.ARRIVED);
        if (arrived.role() == AssignmentRole.PRIMARY) {
            standDownSupport(arrived.caseId());
        }
        return arrived;
    }

    /**
     * The crew sets off with the patient for {@code hospitalId}: ARRIVED to TRANSPORTING. The unit stays ASSIGNED and
     * keeps its claim until the trip is completed. Normally called through {@link TransportCoordinator#start}, which
     * chooses the hospital and moves the case too.
     *
     * @param assignmentId the trip that moves
     * @param hospitalId   the hospital the patient is taken to
     * @param needs        the specialist services the patient needs; empty means nothing special
     * @return the updated trip
     * @throws IllegalArgumentException if an id is blank or {@code needs} is missing
     * @throws IllegalStateException    if there is no such trip, it is not the main (PRIMARY) trip, or it is not
     *                                  ARRIVED
     */
    public Assignment startTransport(String assignmentId, String hospitalId, Set<Capability> needs) {
        Assignment a = find(assignmentId);
        AssignmentStatus from = a.status();
        a.startTransport(hospitalId, needs);                        // refuses before anything changes
        assignments.save(a);
        publisher.publish(new AssignmentStatusChanged(
                a.id(), a.caseId(), a.resourceId(), from, a.status(), clock.instant()));
        return a;
    }

    /**
     * The job is finished, on scene or at the hospital: ARRIVED or TRANSPORTING to DONE. The unit becomes available
     * again.
     *
     * @param assignmentId the trip that ends
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or the move is not allowed
     */
    public Assignment complete(String assignmentId) {
        return move(assignmentId, AssignmentStatus.DONE);
    }

    /**
     * The trip is called off at any stage before it ended (for example, the unit was preempted).
     * The unit becomes available again.
     *
     * @param assignmentId the trip that ends
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or it has already ended
     */
    public Assignment cancel(String assignmentId) {
        return move(assignmentId, AssignmentStatus.CANCELLED);
    }

    /**
     * The crew never acknowledged: ASSIGNED to TIMED_OUT. The unit goes offline.
     * Normally called through {@link ResponseRecovery#onTimedOut(String)}, which also requeues the case.
     *
     * @param assignmentId the trip that ends
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or it is no longer ASSIGNED
     */
    public Assignment timeOut(String assignmentId) {
        return move(assignmentId, AssignmentStatus.TIMED_OUT);
    }

    /**
     * The unit cannot complete the trip (breakdown, lost signal, stalled). The unit goes offline.
     * Normally called through {@link ResponseRecovery#onFailed(String)}, which also requeues the case.
     *
     * @param assignmentId the trip that ends
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip or it has already ended
     */
    public Assignment onFailed(String assignmentId){
        return move(assignmentId, AssignmentStatus.FAILED);
    }

    /**
     * The unit is no longer enough for the case: its trip becomes a first responder's (PRIMARY to SUPPORT) and
     * keeps going. Normally called through {@link ResponseUpgrader}, which also requeues the case for a better unit.
     *
     * @param assignmentId the trip whose role changes
     * @return the updated trip
     * @throws IllegalArgumentException if {@code assignmentId} is blank
     * @throws IllegalStateException    if there is no such trip, it is not PRIMARY, or it has ended
     */
    public Assignment demoteToSupport(String assignmentId) {
        Assignment a = find(assignmentId);
        AssignmentRole from = a.role();
        a.demoteToSupport();                                 // refuses before anything changes
        assignments.save(a);
        publisher.publish(new AssignmentRoleChanged(
                a.id(), a.caseId(), a.resourceId(), from, a.role(), clock.instant()));
        return a;
    }

    private Assignment find(String assignmentId) {
        if (assignmentId == null || assignmentId.isBlank()) {
            throw new IllegalArgumentException("assignmentId is required");
        }
        return assignments.findById(assignmentId)
                .orElseThrow(() -> new IllegalStateException("No assignment " + assignmentId));
    }

    private Assignment move(String assignmentId, AssignmentStatus next) {
        Assignment a = find(assignmentId);
        AssignmentStatus from = a.status();
        a.transitionTo(next);                                // an illegal move throws here, before anything changes
        assignments.save(a);
        if (next.isTerminal()) {                             // the trip ended: the unit leaves it
            ResourceStatus after = (next == AssignmentStatus.TIMED_OUT || next == AssignmentStatus.FAILED) ? ResourceStatus.OFFLINE : ResourceStatus.AVAILABLE;
            releaseUnit(a,after);
        }
        publisher.publish(new AssignmentStatusChanged(
                a.id(), a.caseId(), a.resourceId(), from, next, clock.instant()));
        return a;
    }

    // the main response has taken over: end the first responder's trip so the unit is free again
    private void standDownSupport(String caseId) {
        for (Assignment a : assignments.findActiveByCaseId(caseId)) {
            if (a.role() == AssignmentRole.SUPPORT) {
                move(a.id(), a.status() == AssignmentStatus.ARRIVED ? AssignmentStatus.DONE : AssignmentStatus.CANCELLED);
            }
        }
    }

    private void releaseUnit(Assignment a,ResourceStatus after) {
        claimer.release(a.resourceId(), a.caseId());
        Resource unit = resources.findById(a.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + a.resourceId() + " not found"));
        if (unit.status() == ResourceStatus.ASSIGNED) {      // an OFFLINE unit stays offline
            unit.transitionTo(after);
            resources.save(unit);
        }
    }
}

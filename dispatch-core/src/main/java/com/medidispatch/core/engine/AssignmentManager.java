package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;

/**
 * Moves a trip through its lifecycle and cleans up when it ends.
 * <p>
 * Every method finds the case's active trip, moves it to the next status (illegal moves throw before
 * anything changes), saves it and publishes an {@link AssignmentStatusChanged}. When the trip ends, the
 * unit's claim is released and the unit is returned: to {@code AVAILABLE} after a normal end or a
 * cancellation, to {@code OFFLINE} after a timeout or failure, so a broken or silent unit is never picked
 * again straight away.
 * <p>
 * Trips are looked up by case id, because a case has at most one active trip.
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
     * @param caseId the case whose trip moves
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip or the move is not allowed
     */
    public void onEnRoute(String caseId) {
        move(caseId, AssignmentStatus.EN_ROUTE);
    }

    /**
     * The crew reached the patient: EN_ROUTE to ARRIVED.
     *
     * @param caseId the case whose trip moves
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip or the move is not allowed
     */
    public void onArrived(String caseId) {
        move(caseId, AssignmentStatus.ARRIVED);
    }

    /**
     * The job is finished: ARRIVED to DONE. The unit becomes available again.
     *
     * @param caseId the case whose trip ends
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip or the move is not allowed
     */
    public void complete(String caseId) {
        move(caseId, AssignmentStatus.DONE);
    }

    /**
     * The trip is called off at any stage before it ended (for example, the unit was preempted).
     * The unit becomes available again.
     *
     * @param caseId the case whose trip ends
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip
     */
    public void cancel(String caseId) {
        move(caseId, AssignmentStatus.CANCELLED);
    }
    /**
     * The crew never acknowledged: ASSIGNED to TIMED_OUT. The unit goes offline.
     * Normally called through {@link ResponseRecovery#onTimedOut(String)}, which also requeues the case.
     *
     * @param caseId the case whose trip ends
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip or it is no longer ASSIGNED
     */
    public void timeOut(String caseId) {
        move(caseId, AssignmentStatus.TIMED_OUT);
    }
    /**
     * The unit cannot complete the trip (breakdown, lost signal, stalled). The unit goes offline.
     * Normally called through {@link ResponseRecovery#onFailed(String)}, which also requeues the case.
     *
     * @param caseId the case whose trip ends
     * @throws IllegalArgumentException if {@code caseId} is blank
     * @throws IllegalStateException    if the case has no active trip
     */
    public void onFailed(String caseId){ move(caseId,AssignmentStatus.FAILED);}

    private void move(String caseId, AssignmentStatus next) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        Assignment a = assignments.findActiveByCaseId(caseId)
                .orElseThrow(() -> new IllegalStateException("No active assignment for case " + caseId));
        AssignmentStatus from = a.status();
        a.transitionTo(next);                                // an illegal move throws here, before anything changes
        assignments.save(a);
        if (next.isTerminal()) {                             // DONE or CANCELLED: the unit goes back to the pool
            ResourceStatus after = (next == AssignmentStatus.TIMED_OUT || next == AssignmentStatus.FAILED) ? ResourceStatus.OFFLINE : ResourceStatus.AVAILABLE;
            releaseUnit(a,after);
        }
        publisher.publish(new AssignmentStatusChanged(
                a.id(), a.caseId(), a.resourceId(), from, next, clock.instant()));
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
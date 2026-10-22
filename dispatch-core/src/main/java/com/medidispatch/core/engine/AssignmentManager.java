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

public class AssignmentManager {
    private final AssignmentRepository assignments;
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    private final EventPublisher publisher;
    private final Clock clock;

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

    public void onEnRoute(String caseId) {
        move(caseId, AssignmentStatus.EN_ROUTE);
    }

    public void onArrived(String caseId) {
        move(caseId, AssignmentStatus.ARRIVED);
    }

    public void complete(String caseId) {
        move(caseId, AssignmentStatus.DONE);
    }

    public void cancel(String caseId) {
        move(caseId, AssignmentStatus.CANCELLED);
    }
    public void timeOut(String caseId) {
        move(caseId, AssignmentStatus.TIMED_OUT);
    }
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
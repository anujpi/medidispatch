package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;

public class AssignmentManager {
    private final AssignmentRepository assignments;
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    public AssignmentManager(AssignmentRepository assignments, ResourceRepository resources, ResourceClaimer claimer) {

        if(assignments == null || resources == null || claimer == null){
            throw new IllegalArgumentException("assignments, resources and claimer are required");
        }
        this.assignments = assignments;
        this.resources = resources;
        this.claimer = claimer;
    }
    public void onEnRoute(String caseId) {
        move(caseId, AssignmentStatus.EN_ROUTE);
    }

    public void onArrived(String caseId) {
        move(caseId, AssignmentStatus.ARRIVED);
    }

    public void complete(String caseId) {
        Assignment a = move(caseId, AssignmentStatus.DONE);
        freeUnit(a);
    }

    public void cancel(String caseId) {
        Assignment a = move(caseId, AssignmentStatus.CANCELLED);
        freeUnit(a);
    }
    private Assignment move(String caseId,AssignmentStatus next){
        if(caseId == null || caseId.isBlank()){
            throw new IllegalArgumentException("caseId is required");
        }
        Assignment a = assignments.findActiveByCaseId(caseId).orElseThrow(() -> new IllegalStateException("No active assignment for case " + caseId));
        a.transitionTo(next);
        assignments.save(a);
        return a;
    }
    private void freeUnit(Assignment a){
        claimer.release(a.resourceId(), a.caseId());
        Resource unit = resources.findById(a.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + a.resourceId() + " not found"));
        if (unit.status() == ResourceStatus.ASSIGNED) {        // 2. an OFFLINE unit stays offline
            unit.transitionTo(ResourceStatus.AVAILABLE);
            resources.save(unit);
        }
    }
}

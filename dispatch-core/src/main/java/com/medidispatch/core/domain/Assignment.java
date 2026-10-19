package com.medidispatch.core.domain;

import java.time.Instant;
import java.util.UUID;

public class Assignment {

    private final String id;
    private final String caseId;
    private final String resourceId;
    private final Instant assignedAt;
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

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
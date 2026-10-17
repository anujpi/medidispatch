package com.medidispatch.core.domain;

import java.time.Instant;

public record Assignment(String caseId, String resourceId, Instant assignedAt) {

    public Assignment {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (assignedAt == null) {
            throw new IllegalArgumentException("assignedAt is required");
        }
    }
}
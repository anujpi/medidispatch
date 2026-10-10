package com.medidispatch.core.domain;

public enum ResourceStatus {
    AVAILABLE, ASSIGNED, OFFLINE;

    public boolean canTransitionTo(ResourceStatus next) {
        return switch (this) {
            case AVAILABLE -> next == ASSIGNED || next == OFFLINE;
            case ASSIGNED -> next == AVAILABLE || next == OFFLINE;
            case OFFLINE -> next == AVAILABLE;
        };
    }
}

package com.medidispatch.core.domain;

public enum AssignmentStatus {
    ASSIGNED,EN_ROUTE,ARRIVED,DONE,CANCELLED;

    public boolean canTransitionTo(AssignmentStatus next){
        return switch(this){
            case ASSIGNED -> next == EN_ROUTE || next == CANCELLED;
            case EN_ROUTE -> next == ARRIVED || next == CANCELLED;
            case ARRIVED -> next == DONE || next == CANCELLED;
            case DONE, CANCELLED -> false;
        };
    }
    public boolean isTerminal(){
        return this == DONE || this == CANCELLED;
    }
}

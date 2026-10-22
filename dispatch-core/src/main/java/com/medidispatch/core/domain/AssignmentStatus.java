package com.medidispatch.core.domain;

public enum AssignmentStatus {
    ASSIGNED,EN_ROUTE,ARRIVED,DONE,CANCELLED,TIMED_OUT,FAILED;

    public boolean canTransitionTo(AssignmentStatus next){
        return switch(this){
            case ASSIGNED -> next == EN_ROUTE || next == CANCELLED || next == TIMED_OUT || next == FAILED;
            case EN_ROUTE -> next == ARRIVED || next == CANCELLED || next == FAILED;
            case ARRIVED -> next == DONE || next == CANCELLED || next == FAILED;
            case DONE, CANCELLED,TIMED_OUT, FAILED -> false;
        };
    }
    public boolean isTerminal(){
        return this == DONE || this == CANCELLED || this == TIMED_OUT || this == FAILED;
    }
}

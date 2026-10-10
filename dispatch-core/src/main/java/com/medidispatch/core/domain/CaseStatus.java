package com.medidispatch.core.domain;

public enum CaseStatus {
    MONITORING, ALERTED, RESPONDING, STABILISED, CLOSED, CANCELLED;

    public boolean canTransitionTo(CaseStatus next) {
        return switch (this) {
            case MONITORING -> next == ALERTED || next == CANCELLED;
            case ALERTED    -> next == RESPONDING || next == MONITORING || next == CANCELLED;
            case RESPONDING -> next == STABILISED || next == ALERTED || next == CANCELLED;
            case STABILISED -> next == CLOSED || next == ALERTED;
            case CLOSED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }
}
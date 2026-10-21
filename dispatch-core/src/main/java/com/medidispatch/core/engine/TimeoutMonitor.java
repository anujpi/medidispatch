package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.port.AssignmentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
// will be repeatedly called in the spring application
public class TimeoutMonitor {
    private final AssignmentRepository assignments;
    private final AssignmentManager assignmentManager;
    private final CaseManager caseManager;
    private final Clock clock;
    private final Duration ackTimeout;

    public TimeoutMonitor(AssignmentRepository assignments,
                          AssignmentManager assignmentManager,
                          CaseManager caseManager,
                          Clock clock,
                          Duration ackTimeout) {
        if (assignments == null || assignmentManager == null || caseManager == null
                || clock == null || ackTimeout == null) {
            throw new IllegalArgumentException(
                    "assignments, assignmentManager, caseManager, clock and ackTimeout are required");
        }
        if (ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("ackTimeout must be positive, got " + ackTimeout);
        }
        this.assignments = assignments;
        this.assignmentManager = assignmentManager;
        this.caseManager = caseManager;
        this.clock = clock;
        this.ackTimeout = ackTimeout;
    }
    // returns how many assignments were timed out
    public int checkTimeouts(){
        Instant now = clock.instant();
        int handled = 0;
        for(Assignment a : assignments.findActive()){
            if(a.status() != AssignmentStatus.ASSIGNED){
                continue;
            }
            Instant deadline = a.assignedAt().plus(ackTimeout);
            if(deadline.isAfter(now)){
                continue;
            }
            assignmentManager.timeOut(a.caseId());
            caseManager.onAssignmentLost(a.caseId());
            handled++;
        }
        return handled;
    }
}

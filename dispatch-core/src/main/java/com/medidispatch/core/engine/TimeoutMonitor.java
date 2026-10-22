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
    private final ResponseRecovery recovery;
    private final Clock clock;
    private final Duration ackTimeout;

    public TimeoutMonitor(AssignmentRepository assignments,
                          ResponseRecovery recovery,
                          Clock clock,
                          Duration ackTimeout) {
        if (assignments == null || recovery == null || clock == null || ackTimeout == null) {
            throw new IllegalArgumentException("assignments, recovery, clock and ackTimeout are required");
        }
        if (ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("ackTimeout must be positive, got " + ackTimeout);
        }
        this.assignments = assignments;
        this.recovery = recovery;
        this.clock = clock;
        this.ackTimeout = ackTimeout;
    }

    // returns how many assignments were timed out
    public int checkTimeouts() {
        Instant now = clock.instant();
        int handled = 0;
        for (Assignment a : assignments.findActive()) {
            if (a.status() != AssignmentStatus.ASSIGNED) {
                continue;                                      // the crew has already answered
            }
            Instant deadline = a.assignedAt().plus(ackTimeout);
            if (deadline.isAfter(now)) {
                continue;                                      // not due yet (exactly on time counts as due)
            }
            recovery.onTimedOut(a.caseId());
            handled++;
        }
        return handled;
    }
}
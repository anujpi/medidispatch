package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.port.AssignmentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Gives up on units that never acknowledge their assignment.
 * <p>
 * Meant to be called regularly (a scheduled job in the Spring service). Each check looks at trips that are
 * still ASSIGNED; any whose acknowledgement deadline has passed is timed out through
 * {@link ResponseRecovery}, which takes the unit offline and requeues the case so the next unit is sent.
 * Trips that are already EN_ROUTE are {@link TripMonitor}'s job.
 */
public class TimeoutMonitor {
    private final AssignmentRepository assignments;
    private final ResponseRecovery recovery;
    private final Clock clock;
    private final Duration ackTimeout;

    /**
     * Creates the monitor.
     *
     * @param assignments where trips are stored
     * @param recovery    handles a timed-out trip and its case
     * @param clock       source of the current time
     * @param ackTimeout  how long a unit has to acknowledge; must be positive
     * @throws IllegalArgumentException if an argument is missing or the timeout is not positive
     */
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

    /**
     * Times out every ASSIGNED trip whose deadline has passed. Exactly on the deadline counts as passed.
     *
     * @return how many trips were timed out
     */
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
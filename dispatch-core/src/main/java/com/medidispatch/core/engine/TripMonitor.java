package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Watches units that are on their way and fails the trip when a unit goes silent or stops making progress.
 * <p>
 * Meant to be called regularly (a scheduled job in the Spring service). Two separate checks, because a
 * stuck ambulance with working GPS is not silent:
 * <ul>
 *   <li><b>signal lost</b>: no position report for {@code signalTimeout};</li>
 *   <li><b>stalled</b>: reports keep coming but the unit has not really moved for {@code stallTimeout}.</li>
 * </ul>
 * Both are measured from the later of the unit's last report or move and the trip's start, so data from
 * before the trip is ignored. A failed trip goes through {@link ResponseRecovery#onFailed(String)}, by its
 * assignment id.
 * Only EN_ROUTE trips are checked: ASSIGNED trips are {@link TimeoutMonitor}'s job, and an ARRIVED crew is
 * supposed to stand still.
 */
public class TripMonitor {
    private final AssignmentRepository assignments;
    private final ResourceRepository resource;
    private final ResponseRecovery recovery;
    private final Clock clock;
    private final Duration signalTimeout;
    private final Duration stallTimeout;

    /**
     * Creates the monitor.
     *
     * @param assignments   where trips are stored
     * @param resource      where units (and their last report and move times) are stored
     * @param recovery      handles a failed trip and its case
     * @param clock         source of the current time
     * @param signalTimeout how long a unit may stay silent; must be positive
     * @param stallTimeout  how long a unit may report without moving; must be positive
     * @throws IllegalArgumentException if an argument is missing or a timeout is not positive
     */
    public TripMonitor(AssignmentRepository assignments,  ResourceRepository resource,ResponseRecovery recovery, Clock clock, Duration signalTimeout, Duration stallTimeout) {
        if (assignments == null || resource == null || recovery == null || clock == null
                || signalTimeout == null || stallTimeout == null) {
            throw new IllegalArgumentException("assignments, resource, recovery, clock, signalTimeout and stallTimeout are required");
        }
        if (signalTimeout.isZero() || signalTimeout.isNegative()) {
            throw new IllegalArgumentException("signalTimeout must be positive, got " + signalTimeout);
        }
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("stallTimeout must be positive, got " + stallTimeout);
        }
        this.assignments = assignments;
        this.recovery = recovery;
        this.resource = resource;
        this.clock = clock;
        this.signalTimeout = signalTimeout;
        this.stallTimeout = stallTimeout;
    }
    /**
     * Fails every EN_ROUTE trip whose unit has lost signal or stalled. Exactly on a deadline counts as passed.
     *
     * @return how many trips were failed
     * @throws IllegalStateException if an en-route trip's unit is missing
     */
    public int  checkTrips(){
        Instant now = clock.instant();
        int handled = 0;
        for(Assignment a: assignments.findActive()){
            if(a.status() != AssignmentStatus.EN_ROUTE){
                continue;
            }
            Resource unit = resource.findById(a.resourceId()).orElseThrow(()-> new IllegalStateException("Unit "+ a.resourceId()+" not found"));
            Instant silentSince = laterOf(unit.lastSeenAt(), a.assignedAt());
            Instant stationarySince = laterOf(unit.lastMovedAt(), a.assignedAt());

            boolean signalLost = !silentSince.plus(signalTimeout).isAfter(now);
            boolean stalled = !stationarySince.plus(stallTimeout).isAfter(now);

            if (signalLost || stalled) {
                recovery.onFailed(a.id());
                handled++;
            }
        }
        return handled;

    }
    // the later of a reported time and the trip start, so data from before the trip is ignored
    private Instant laterOf(Optional<Instant> reported, Instant tripStart) {
        if (reported.isPresent() && reported.get().isAfter(tripStart)) {
            return reported.get();
        }
        return tripStart;
    }
}

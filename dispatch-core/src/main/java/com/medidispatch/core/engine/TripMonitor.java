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

public class TripMonitor {
    private final AssignmentRepository assignments;
    private final ResourceRepository resource;
    private final ResponseRecovery recovery;
    private final Clock clock;
    private final Duration signalTimeout;
    private final Duration stallTimeout;

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
                recovery.onFailed(a.caseId());
                handled++;
            }
        }
        return handled;

    }
    private Instant laterOf(Optional<Instant> reported, Instant tripStart) {
        if (reported.isPresent() && reported.get().isAfter(tripStart)) {
            return reported.get();
        }
        return tripStart;
    }
}

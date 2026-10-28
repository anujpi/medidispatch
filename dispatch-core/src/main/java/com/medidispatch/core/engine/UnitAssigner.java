package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The one place that turns "send this unit" into stored state: claim the unit, mark it ASSIGNED, save a new
 * trip and announce it. Shared by {@link Dispatcher} (main response) and {@link SupportDispatcher}
 * (first responders), so both start trips exactly the same way.
 */
final class UnitAssigner {
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    private final AssignmentRepository assignments;
    private final EventPublisher publisher;
    private final Clock clock;

    UnitAssigner(ResourceRepository resources, ResourceClaimer claimer, AssignmentRepository assignments,
                 EventPublisher publisher, Clock clock) {
        this.resources = resources;
        this.claimer = claimer;
        this.assignments = assignments;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Tries the candidates in order and sends the first one whose claim is won.
     *
     * @param c          the case to serve
     * @param candidates free units, best first
     * @param role       the role of the new trip
     * @return the new trip, or empty if every claim was lost or there were no candidates
     */
    Optional<Assignment> assignFirst(Case c, List<Resource> candidates, AssignmentRole role) {
        for (Resource candidate : candidates) {
            if (claimer.tryClaim(candidate.id(), c.id())) {
                candidate.transitionTo(ResourceStatus.ASSIGNED);
                resources.save(candidate);
                Assignment assignment = new Assignment(c.id(), candidate.id(), role, clock.instant());
                assignments.save(assignment);
                publisher.publish(new UnitAssigned(assignment.id(), c.id(), candidate.id(), role, clock.instant()));
                return Optional.of(assignment);
            }
        }
        return Optional.empty();
    }
}

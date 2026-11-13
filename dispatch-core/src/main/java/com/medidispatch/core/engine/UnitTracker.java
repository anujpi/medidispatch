package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;

/**
 * Records what units report about themselves: their position, and that a repaired unit is back in service.
 * It only stores facts; {@link TripMonitor} decides what the positions mean.
 */
public class UnitTracker {
    private final ResourceRepository resources;
    private final Clock clock;

    /**
     * Creates the tracker.
     *
     * @param resources where units are stored
     * @param clock     source of the time stamped on each report
     * @throws IllegalArgumentException if any argument is missing
     */
    public UnitTracker(ResourceRepository resources,Clock clock){
        if(resources == null || clock == null){
            throw new IllegalArgumentException("Resources and clock cant be null");
        }
        this.resources = resources;
        this.clock = clock;
    }
    /**
     * Stores a position report, stamped with the current time.
     *
     * @param resourceId the unit that reported
     * @param where      the reported position
     * @throws IllegalArgumentException if the id is blank or the position is missing
     * @throws IllegalStateException    if no unit has that id
     * @see Resource#reportLocation(Location, java.time.Instant)
     */
    public void onLocationReported(String resourceId, Location where){
        if(resourceId == null || resourceId.isBlank()){
            throw new IllegalArgumentException("ResourceId cant be null or blank");
        }
        if(where == null){
            throw new IllegalArgumentException("Location is required");
        }
        Resource unit = resources.findById(resourceId).orElseThrow(()-> new IllegalStateException("Unit" + resourceId + " not found"));
        unit.reportLocation(where,clock.instant());
        resources.save(unit);
    }

    /**
     * A unit that was taken out of service (after a timeout or a failure) is repaired and can be sent again:
     * OFFLINE to AVAILABLE.
     *
     * @param resourceId the unit that is back
     * @throws IllegalArgumentException if the id is blank
     * @throws IllegalStateException    if no unit has that id, or it is not OFFLINE; nothing is changed
     */
    public void onBackInService(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("ResourceId cant be null or blank");
        }
        Resource unit = resources.findById(resourceId)
                .orElseThrow(() -> new IllegalStateException("Unit " + resourceId + " not found"));
        if (unit.status() != ResourceStatus.OFFLINE) {
            throw new IllegalStateException("Unit " + resourceId + " is " + unit.status() + ", not OFFLINE");
        }
        unit.transitionTo(ResourceStatus.AVAILABLE);
        resources.save(unit);
    }
}

package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.port.ResourceRepository;

import java.time.Clock;

public class UnitTracker {
    private final ResourceRepository resources;
    private final Clock clock;
    public UnitTracker(ResourceRepository resources,Clock clock){
        if(resources == null || clock == null){
            throw new IllegalArgumentException("Resources and clock cant be null");
        }
        this.resources = resources;
        this.clock = clock;
    }
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
}

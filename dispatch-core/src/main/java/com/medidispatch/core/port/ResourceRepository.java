package com.medidispatch.core.port;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ResourceRepository {
    void save(Resource resource);
    Optional<Resource> findById(String id);
    List<Resource> findAvailableNear(Location center, double radiusKm, Set<ResourceType> types);
    List<Resource> findAssignedNear(Location center,double radiusKm,Set<ResourceType> types);
}

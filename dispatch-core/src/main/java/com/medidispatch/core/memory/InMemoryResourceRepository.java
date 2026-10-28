package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.port.ResourceRepository;

import java.util.*;

/**
 * In-memory {@link ResourceRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * The "near" searches check every stored unit, so they get slower as the fleet grows. That is fine here;
 * a real adapter answers the same questions with a spatial index (Redis GEO, PostGIS).
 */
public class InMemoryResourceRepository implements ResourceRepository {

    private final Map<String,Resource> byId = new HashMap<>();
    private record Candidate(Resource resource,double distanceKm){}


    @Override
    public void save(Resource resource) {
        if(resource == null){
            throw new IllegalArgumentException("Resource is required");
        }
        byId.put(resource.id(), resource);
    }

    @Override
    public Optional<Resource> findById(String id) {
        if(id == null){
            throw new IllegalArgumentException("id is required");
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Resource> findAvailableNear(Location center, double radiusKm, Set<ResourceType> types) {
        return findNear(ResourceStatus.AVAILABLE,center,radiusKm,types);
    }

    @Override
    public List<Resource> findAssignedNear(Location center, double radiusKm, Set<ResourceType> types) {
        return findNear(ResourceStatus.ASSIGNED,center,radiusKm,types);
    }

    // shared by both searches: same rules, only the wanted status differs
    private List<Resource> findNear(ResourceStatus resourceStatus, Location center, double radiusKm, Set<ResourceType> types) {
        if (center == null) {
            throw new IllegalArgumentException("center is required");
        }
        if (types == null) {
            throw new IllegalArgumentException("types is required");
        }
        if (radiusKm <= 0) {
            throw new IllegalArgumentException("radiusKm must be positive, got " + radiusKm);
        }
        //keep only resources that pass all three checks
        List<Candidate> found = new ArrayList<>();
        for(Resource r: byId.values()){
            if (r.status()!=resourceStatus) continue;               // must be free
            if (!types.contains(r.type())) continue;      // must be a requested type
            double distance = center.distanceKmTo(r.location());
            if (distance > radiusKm) continue;            // must be within range (edge is included)
            found.add(new Candidate(r, distance));
        }
        found.sort(Comparator.comparingDouble(Candidate::distanceKm).thenComparing(a -> a.resource().id()));
        List<Resource> result = new ArrayList<>();
        for (Candidate c : found) {
            result.add(c.resource());
        }
        return result;
    }

}

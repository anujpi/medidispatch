package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.port.FacilityRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link FacilityRepository} kept in a map, for core tests and the simulator. Facilities are immutable records, so
 * handing out the stored objects is safe.
 */
public class InMemoryFacilityRepository implements FacilityRepository {

    private final Map<String, Facility> byId = new HashMap<>();

    @Override
    public void save(Facility facility) {
        if (facility == null) {
            throw new IllegalArgumentException("facility is required");
        }
        byId.put(facility.id(), facility);
    }

    @Override
    public Optional<Facility> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Facility> findAll() {
        List<Facility> all = new ArrayList<>(byId.values());
        all.sort((a, b) -> a.id().compareTo(b.id()));
        return all;
    }
}

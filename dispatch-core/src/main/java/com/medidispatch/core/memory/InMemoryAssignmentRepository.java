package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.port.AssignmentRepository;

import java.util.*;

/**
 * In-memory {@link AssignmentRepository} for tests and the simulator. Not thread-safe.
 * <p>
 * The "active" lookups scan every trip ever stored; a database adapter would index by status and case.
 */
public class InMemoryAssignmentRepository implements AssignmentRepository {

    private final Map<String, Assignment> byId = new HashMap<>();

    @Override
    public void save(Assignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment is required");
        }
        byId.put(assignment.id(), assignment);
    }

    @Override
    public Optional<Assignment> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Assignment> findActiveByCaseId(String caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId is required");
        }
        for (Assignment a : byId.values()) {
            if (a.caseId().equals(caseId) && !a.status().isTerminal()) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Assignment> findActive() {
        List<Assignment> active = new ArrayList<>();
        for(Assignment a:byId.values()){
            if(!a.status().isTerminal()){
                active.add(a);
            }
        }
        return active;
    }
}
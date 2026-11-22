package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.port.AssignmentRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AssignmentRepository} (trips) on Postgres through JPA: the one port where JPA fits best (a flat table, no
 * map data, no column shared with another adapter, plain lookups that Spring Data writes from method names).
 *
 * <p>Saves use {@code saveAndFlush}, not {@code save}: JPA normally holds a change in memory until commit, and a
 * JdbcClient query in the same transaction would not see it. Flushing makes the SQL run at the moment the engine
 * calls {@code save()}, like the JDBC adapters, and a broken rule (one active trip per unit) fails right there.
 *
 * <p>Results are always mapped to domain trips at once; no entity leaves this class.
 */
// ponytail: ids are chosen by the domain, so Spring Data cannot tell a new trip from an existing one and every save
// is SELECT then INSERT/UPDATE (merge). Fine at a few trips a minute; implement Persistable (isNew) if it ever shows.
public class JpaAssignmentRepository implements AssignmentRepository {

    /** Every status that is not terminal, built from {@link AssignmentStatus#isTerminal()} (one source of truth). */
    private static final Set<AssignmentStatus> ACTIVE = activeStatuses();

    private final TripJpaRepository trips;

    /**
     * @param trips the Spring Data repository Spring builds from {@link TripJpaRepository}
     */
    public JpaAssignmentRepository(TripJpaRepository trips) {
        this.trips = trips;
    }

    /**
     * Stores the trip, or updates the stored one with the same id, and sends the SQL immediately.
     *
     * @param assignment the trip
     * @throws IllegalArgumentException if {@code assignment} is null
     * @throws org.springframework.dao.DataIntegrityViolationException if the database refuses it (for example a
     *         second active trip for one unit)
     */
    @Override
    public void save(Assignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment is required");
        }
        trips.saveAndFlush(TripEntity.from(assignment));
    }

    /**
     * @param id the trip id
     * @return the trip, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is null
     */
    @Override
    public Optional<Assignment> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return trips.findById(id).map(e -> e.toAssignment());
    }

    /**
     * @param caseId the case
     * @return its trips that have not ended (a main response and possibly a first responder), oldest first
     * @throws IllegalArgumentException if {@code caseId} is null
     */
    @Override
    public List<Assignment> findActiveByCaseId(String caseId) {
        if (caseId == null) {
            throw new IllegalArgumentException("caseId is required");
        }
        return toAssignments(trips.findByCaseIdAndStatusInOrderByAssignedAtAscIdAsc(caseId, ACTIVE));
    }

    /**
     * @param resourceId the unit
     * @return the unit's trip that has not ended, or empty
     * @throws IllegalArgumentException if {@code resourceId} is null
     */
    @Override
    public Optional<Assignment> findActiveByResourceId(String resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        return trips.findByUnitIdAndStatusIn(resourceId, ACTIVE).map(e -> e.toAssignment());
    }

    /**
     * @return every trip that has not ended, oldest first, then by id (in memory the order was unspecified)
     */
    @Override
    public List<Assignment> findActive() {
        return toAssignments(trips.findByStatusInOrderByAssignedAtAscIdAsc(ACTIVE));
    }

    private static List<Assignment> toAssignments(List<TripEntity> entities) {
        List<Assignment> result = new ArrayList<>();
        for (TripEntity e : entities) {
            result.add(e.toAssignment());
        }
        return result;
    }

    private static Set<AssignmentStatus> activeStatuses() {
        Set<AssignmentStatus> active = EnumSet.noneOf(AssignmentStatus.class);
        for (AssignmentStatus s : AssignmentStatus.values()) {
            if (!s.isTerminal()) {
                active.add(s);
            }
        }
        return active;
    }
}

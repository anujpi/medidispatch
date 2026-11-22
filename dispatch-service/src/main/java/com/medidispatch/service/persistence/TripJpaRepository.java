package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link TripEntity}: only an interface. At startup Spring Data creates the class behind it.
 *
 * <p>{@link JpaRepository} brings {@code findById}, {@code saveAndFlush} and the rest. The methods below are
 * "derived queries": Spring Data reads the method NAME and builds the query from it ({@code findBy} starts it,
 * {@code CaseId} / {@code UnitId} / {@code Status} are fields, {@code And} joins conditions, {@code In} takes a
 * collection, {@code OrderBy...Asc} sorts). A field name that does not exist fails at startup, not at runtime.
 *
 * <p>Use it through {@link JpaAssignmentRepository}, which turns entities into domain trips.
 */
public interface TripJpaRepository extends JpaRepository<TripEntity, String> {

    /**
     * {@code WHERE case_id = ? AND status IN (...) ORDER BY assigned_at, id}
     *
     * @param caseId   the case
     * @param statuses the statuses to include
     * @return the matching trips, oldest first
     */
    List<TripEntity> findByCaseIdAndStatusInOrderByAssignedAtAscIdAsc(String caseId, Collection<AssignmentStatus> statuses);

    /**
     * {@code WHERE unit_id = ? AND status IN (...)}. Optional because at most one row can match for the active
     * statuses: the database's one_active_trip_per_unit index guarantees it (Spring Data would throw on two).
     *
     * @param unitId   the unit
     * @param statuses the statuses to include
     * @return the matching trip, or empty
     */
    Optional<TripEntity> findByUnitIdAndStatusIn(String unitId, Collection<AssignmentStatus> statuses);

    /**
     * {@code WHERE status IN (...) ORDER BY assigned_at, id}
     *
     * @param statuses the statuses to include
     * @return the matching trips, oldest first
     */
    List<TripEntity> findByStatusInOrderByAssignedAtAscIdAsc(Collection<AssignmentStatus> statuses);
}

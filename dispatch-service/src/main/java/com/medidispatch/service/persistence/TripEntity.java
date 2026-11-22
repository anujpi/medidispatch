package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * One row of {@code assignments} as a JPA entity: Hibernate maps it to the table and writes the SQL.
 *
 * <p>A separate class from the domain {@link Assignment} on purpose: dispatch-core stays pure Java (no JPA
 * annotations), and the domain's rules (final id, legal status moves only) are not bypassed by Hibernate setting
 * fields. {@link #from} and {@link #toAssignment} copy between the two; {@link JpaAssignmentRepository} never lets an
 * entity out, so nothing can change a managed entity by accident (Hibernate would write that change by itself at
 * the next flush: "dirty checking").
 *
 * <p>No {@code @Column}s: Boot's naming rule turns {@code caseId} into {@code case_id}, {@code assignedAt} into
 * {@code assigned_at}. The domain's {@code resourceId} is {@code unitId} here, after the column. {@code Instant} maps to
 * {@code timestamptz} by itself. {@code spring.jpa.hibernate.ddl-auto=validate} checks all of it at startup.
 */
@Entity
@Table(name = "assignments")
public class TripEntity {

    /** Chosen by the domain (a UUID), so there is no {@code @GeneratedValue}. */
    @Id
    private String id;

    private String caseId;

    private String unitId;

    /** STRING stores 'PRIMARY'. Never the default ORDINAL: it stores the position, which shifts if a value is added. */
    @Enumerated(EnumType.STRING)
    private AssignmentRole role;

    @Enumerated(EnumType.STRING)
    private AssignmentStatus status;

    private Instant assignedAt;

    /** NULL until the trip goes to TRANSPORTING. */
    private String hospitalId;

    /** A {@code text[]} column; Hibernate maps a Java array to a Postgres array by itself. */
    private String[] needs;

    /** Hibernate creates entities through a no-argument constructor (and the class must not be final). */
    protected TripEntity() {
    }

    /**
     * @param a a domain trip
     * @return a new entity holding the same values, ready to save
     */
    static TripEntity from(Assignment a) {
        TripEntity e = new TripEntity();
        e.id = a.id();
        e.caseId = a.caseId();
        e.unitId = a.resourceId();
        e.role = a.role();
        e.status = a.status();
        e.assignedAt = a.assignedAt();
        e.hospitalId = a.hospitalId().orElse(null);
        e.needs = JdbcFacilityRepository.toNames(a.needs());
        return e;
    }

    /**
     * @return the domain trip this row stands for, rebuilt with {@link Assignment#restore}
     */
    Assignment toAssignment() {
        Set<Capability> needed = EnumSet.noneOf(Capability.class);
        for (String name : needs) {
            needed.add(Capability.valueOf(name));
        }
        return Assignment.restore(id, caseId, unitId, role, status, assignedAt, hospitalId, needed);
    }
}

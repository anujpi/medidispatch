package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.port.*;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.response.ResponsePlan;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Sends a first responder (a nurse or doctor) alongside a case's main response, to start care before the
 * ambulance arrives.
 * <p>
 * Best-effort by design: it never queues, never preempts, and finding nobody is a normal outcome, not an
 * error. A responder is only sent when:
 * <ul>
 *   <li>the case's {@link ResponsePlan} asks for support;</li>
 *   <li>the case does not already have an active SUPPORT trip (for example after the ambulance was
 *       replaced), so nobody is sent twice;</li>
 *   <li>the responder is strictly closer to the patient than the primary unit, because one who arrives
 *       after the ambulance only takes a nurse away from other patients.</li>
 * </ul>
 * Support types are tried in plan order, each with a ring search capped at the primary unit's distance.
 */
public class SupportDispatcher {
    private final CaseRepository cases;
    private final PatientRepository patients;
    private final ResourceRepository resources;
    private final AssignmentRepository assignments;
    private final ResponseDecider decider;
    private final UnitAssigner assigner;

    /**
     * Creates the support dispatcher.
     *
     * @param cases       where cases are stored
     * @param patients    where the patient's location comes from
     * @param resources   where units are stored and searched by location
     * @param claimer     the atomic "this unit is mine" lock
     * @param assignments where trips are stored
     * @param decider     decides whether a case wants a first responder, and which kinds
     * @param publisher   where new assignments are announced
     * @param clock       source of assignment times
     * @throws IllegalArgumentException if any argument is missing
     */
    public SupportDispatcher(CaseRepository cases,
                             PatientRepository patients,
                             ResourceRepository resources,
                             ResourceClaimer claimer,
                             AssignmentRepository assignments,
                             ResponseDecider decider,
                             EventPublisher publisher,
                             Clock clock) {
        if (cases == null || patients == null || resources == null || claimer == null || assignments == null
                || decider == null || publisher == null || clock == null) {
            throw new IllegalArgumentException(
                    "cases, patients, resources, claimer, assignments, decider, publisher and clock are required");
        }
        this.cases = cases;
        this.patients = patients;
        this.resources = resources;
        this.assignments = assignments;
        this.decider = decider;
        this.assigner = new UnitAssigner(resources, claimer, assignments, publisher, clock);
    }

    /**
     * Sends a first responder for the case served by {@code primary}, if the plan wants one and one is closer.
     *
     * @param primary the case's main-response trip that was just started
     * @return the new SUPPORT trip, or empty if no responder is wanted, one is already on the case,
     *         or nobody free is closer than the primary unit
     * @throws IllegalArgumentException if {@code primary} is missing or is not a PRIMARY trip
     * @throws IllegalStateException    if the case, its patient or the primary unit is missing
     */
    public Optional<Assignment> sendSupportFor(Assignment primary) {
        if (primary == null) {
            throw new IllegalArgumentException("primary trip is required");
        }
        if (primary.role() != AssignmentRole.PRIMARY) {
            throw new IllegalArgumentException("first responders are only sent alongside a PRIMARY trip");
        }
        Case c = cases.findById(primary.caseId())
                .orElseThrow(() -> new IllegalStateException("Case " + primary.caseId() + " not found"));
        Patient patient = patients.findById(c.patientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Patient " + c.patientId() + " not found for case " + c.id()));

        ResponsePlan plan = decider.decide(c, patient);
        if (!plan.wantsSupport()) {
            return Optional.empty();
        }
        for (Assignment a : assignments.findActiveByCaseId(c.id())) {
            if (a.role() == AssignmentRole.SUPPORT) {
                return Optional.empty();                      // one first responder per case is enough
            }
        }

        Resource primaryUnit = resources.findById(primary.resourceId())
                .orElseThrow(() -> new IllegalStateException("Unit " + primary.resourceId() + " not found"));
        Location where = patient.location();
        double primaryKm = where.distanceKmTo(primaryUnit.location());
        for (ResourceType type : plan.supportTypes()) {
            Optional<Assignment> sent = tryTypeCloserThan(c, where, type, primaryKm);
            if (sent.isPresent()) {
                return sent;
            }
        }
        return Optional.empty();
    }

    // one responder type, ring by ring, but never as far as the primary unit
    private Optional<Assignment> tryTypeCloserThan(Case c, Location where, ResourceType type, double limitKm) {
        if (limitKm <= 0) {
            return Optional.empty();                          // the primary unit is already at the patient
        }
        double radius = SearchRings.first(limitKm);
        Optional<Assignment> sent = tryWithin(c, where, type, radius, limitKm);
        while (sent.isEmpty() && radius < limitKm) {
            radius = SearchRings.next(radius, limitKm);
            sent = tryWithin(c, where, type, radius, limitKm);
        }
        return sent;
    }

    // free responders of this type inside the radius and strictly closer than the primary, nearest first
    private Optional<Assignment> tryWithin(Case c, Location where, ResourceType type, double radiusKm, double limitKm) {
        List<Resource> closer = new ArrayList<>();
        for (Resource r : resources.findAvailableNear(where, radiusKm, Set.of(type))) {
            if (where.distanceKmTo(r.location()) < limitKm) {
                closer.add(r);                                // a tie with the primary is not "closer"
            }
        }
        return assigner.assignFirst(c, closer, AssignmentRole.SUPPORT);
    }
}

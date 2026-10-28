package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.port.*;
import com.medidispatch.core.response.ResponseDecider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Frees a unit for a worse case by taking it from a less urgent one. It does not send the unit;
 * the {@link Dispatcher} does that afterwards.
 * <p>
 * Rules for a unit to be taken:
 * <ul>
 *   <li>its trip is ASSIGNED or EN_ROUTE (a crew that has arrived is never taken);</li>
 *   <li>its case is <em>strictly</em> less urgent than the waiting case (equal bands never steal from each
 *       other, so two cases cannot keep taking a unit back and forth);</li>
 *   <li>its type is one of the waiting case's primary types in its
 *       {@link com.medidispatch.core.response.ResponsePlan}.</li>
 * </ul>
 * The search widens ring by ring around the waiting patient and the nearest ring with a candidate wins.
 * Inside that ring the least urgent case loses its unit first, then the nearest unit, then the trip id.
 * The taken trip is cancelled, the unit becomes available and the other case goes back in the queue.
 */
public class Preemptor {
    private final AssignmentRepository assignments;
    private final CaseRepository cases;
    private final ResourceRepository resources;
    private final PatientRepository patients;
    private final ResponseDecider decider;
    private final ResponseRecovery recovery;
    private final double searchRadiusKm;

    private record Victim(Assignment trip, Case victimCase, double distanceKm) {}

    /**
     * Creates the preemptor.
     *
     * @param assignments    where trips are stored (to find the trip each busy unit is on)
     * @param cases          where cases are stored (to compare urgency)
     * @param resources      where units are stored and searched by location
     * @param patients       where the waiting patient's location comes from
     * @param decider        decides which unit types the waiting case may take
     * @param recovery       cancels the taken trip and requeues its case
     * @param searchRadiusKm the furthest a unit may be from the waiting patient; must be positive
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    public Preemptor(AssignmentRepository assignments,
                     CaseRepository cases,
                     ResourceRepository resources,
                     PatientRepository patients,
                     ResponseDecider decider,
                     ResponseRecovery recovery,
                     double searchRadiusKm) {
        if (assignments == null || cases == null || resources == null
                || patients == null || decider == null || recovery == null) {
            throw new IllegalArgumentException(
                    "assignments, cases, resources, patients, decider and recovery are required");
        }
        if (searchRadiusKm <= 0) {
            throw new IllegalArgumentException("searchRadiusKm must be positive, got " + searchRadiusKm);
        }
        this.assignments = assignments;
        this.cases = cases;
        this.resources = resources;
        this.patients = patients;
        this.decider = decider;
        this.recovery = recovery;
        this.searchRadiusKm = searchRadiusKm;
    }

    /**
     * Takes a unit from a less urgent case so the waiting case can be served.
     *
     * @param waiting the case that could not get a free unit
     * @return {@code true} if a unit was freed (the caller should dispatch again);
     *         {@code false} if nothing could be taken, in which case nothing changed
     * @throws IllegalArgumentException if {@code waiting} is missing
     * @throws IllegalStateException    if the waiting patient is missing, or the stored data is inconsistent
     *                                  (an assigned unit without an active trip, or a trip without its case)
     */
    public boolean makeRoomFor(Case waiting) {
        if (waiting == null) {
            throw new IllegalArgumentException("waiting case is required");
        }
        Patient patient = patients.findById(waiting.patientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Patient " + waiting.patientId() + " not found for case " + waiting.id()));
        // any primary type in the plan may be taken; an empty plan allows nothing, so nothing is taken
        Set<ResourceType> allowed = decider.decide(waiting, patient).allowedTypes();

        // look close first, widen the ring only while nobody can be taken
        double radius = SearchRings.first(searchRadiusKm);
        List<Victim> victims = victimsWithin(patient.location(), radius, waiting.band(), allowed);
        while (victims.isEmpty() && radius < searchRadiusKm) {
            radius = SearchRings.next(radius, searchRadiusKm);
            victims = victimsWithin(patient.location(), radius, waiting.band(), allowed);
        }
        if (victims.isEmpty()) {
            return false;
        }
        // inside the ring: least urgent case first, then the nearest unit, then the id so the choice is repeatable
        victims.sort((x, y) -> {
            int byBand = x.victimCase().band().compareTo(y.victimCase().band());
            if (byBand != 0) return byBand;
            int byDistance = Double.compare(x.distanceKm(), y.distanceKm());
            if (byDistance != 0) return byDistance;
            return x.trip().id().compareTo(y.trip().id());
        });
        recovery.onPreempted(victims.get(0).trip().id());
        return true;
    }

    private List<Victim> victimsWithin(Location center, double radiusKm, RiskBand waitingBand, Set<ResourceType> allowed) {
        List<Victim> victims = new ArrayList<>();
        for (Resource unit : resources.findAssignedNear(center, radiusKm, allowed)) {
            Assignment trip = assignments.findActiveByResourceId(unit.id())
                    .orElseThrow(() -> new IllegalStateException("Unit " + unit.id() + " is ASSIGNED but has no active trip"));
            // a crew that has arrived is with a patient: never take it
            if (trip.status() != AssignmentStatus.ASSIGNED && trip.status() != AssignmentStatus.EN_ROUTE) {
                continue;
            }
            Case victimCase = cases.findById(trip.caseId())
                    .orElseThrow(() -> new IllegalStateException("Case " + trip.caseId() + " not found"));
            if (victimCase.band().compareTo(waitingBand) >= 0) {
                continue;                                     // only strictly less urgent cases can lose a unit
            }
            victims.add(new Victim(trip, victimCase, center.distanceKmTo(unit.location())));
        }
        return victims;
    }
}

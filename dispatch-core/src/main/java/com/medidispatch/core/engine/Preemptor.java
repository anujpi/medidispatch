package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.port.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
// frees a unit for a worst case , taking it from a less urgent one.
public class Preemptor {
    private final AssignmentRepository assignments;
    private final CaseRepository cases;
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    private final PatientRepository patients;
    private final ResponseRecovery recovery;
    private final double searchRadiusKm;
    private record Victim(Assignment trip, Case victimCase, double distanceKm) {}
    public Preemptor(AssignmentRepository assignments, CaseRepository cases, ResourceRepository resources, ResourceClaimer claimer,PatientRepository patients, ResponseRecovery recovery, double searchRadiusKm) {
        if(assignments == null || cases == null || resources == null || patients == null || recovery == null){
            throw new IllegalArgumentException("Assignments,cases,resources,patients,recovery is required");
        }
        this.assignments = assignments;
        this.cases = cases;
        this.resources = resources;
        this.claimer = claimer;
        this.patients = patients;
        this.recovery = recovery;
        this.searchRadiusKm = searchRadiusKm;
    }
    // -> need to finish this
    public boolean makeRoomFor(Case waiting){
        if (waiting == null) {
            throw new IllegalArgumentException("waiting case is required");
        }
        Patient patient = patients.findById(waiting.patientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Patient " + waiting.patientId() + " not found for case " + waiting.id()));
        Set<ResourceType> allowed = ResponseTypes.forBand(waiting.band());

        // look close first, widen the ring only while nobody can be taken
        double radius = SearchRings.first(searchRadiusKm);
        List<Victim> victims = victimsWithin(patient.location(), radius, waiting.band(), allowed);
        while (victims.isEmpty() && radius < searchRadiusKm) {
            radius = SearchRings.next(radius,searchRadiusKm);
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
        recovery.onPreempted(victims.get(0).victimCase().id());
        return true;
    }
    private List<Victim> victimsWithin(Location center, double radiusKm, RiskBand waitingBand, Set<ResourceType> allowed) {
        List<Victim> victims = new ArrayList<>();
        for (Resource unit : resources.findAssignedNear(center, radiusKm, allowed)) {
            String caseId = claimer.claimedBy(unit.id())
                    .orElseThrow(() -> new IllegalStateException("Unit " + unit.id() + " is ASSIGNED but holds no claim"));
            Assignment trip = assignments.findActiveByCaseId(caseId)
                    .orElseThrow(() -> new IllegalStateException("No active trip for case " + caseId));
            // a crew that has arrived is with a patient: never take it
            if (trip.status() != AssignmentStatus.ASSIGNED && trip.status() != AssignmentStatus.EN_ROUTE) {
                continue;
            }
            Case victimCase = cases.findById(caseId)
                    .orElseThrow(() -> new IllegalStateException("Case " + caseId + " not found"));
            if (victimCase.band().compareTo(waitingBand) >= 0) {
                continue;                                     // only strictly less urgent cases can lose a unit
            }
            victims.add(new Victim(trip, victimCase, center.distanceKmTo(unit.location())));
        }
        return victims;
    }

}

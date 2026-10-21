package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.port.*;
import com.medidispatch.core.event.UnitAssigned;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class Dispatcher {
    private final CaseQueue queue;
    private final PatientRepository patients;
    private final ResourceRepository resources;
    private final ResourceClaimer claimer;
    private final EventPublisher publisher;
    private final Clock clock;
    private final double searchRadiusKm;
    private final AssignmentRepository assignments;
    public Dispatcher(CaseQueue queue,
                      PatientRepository patients,
                      ResourceRepository resources,
                      ResourceClaimer claimer,
                      AssignmentRepository assignments,
                      EventPublisher publisher,
                      Clock clock,
                      double searchRadiusKm) {
        if (queue == null || patients == null || resources == null || publisher == null || assignments == null || claimer == null || clock == null) {
            throw new IllegalArgumentException("queue, patients, resources, claimer and clock are required");
        }
        if (searchRadiusKm <= 0) {
            throw new IllegalArgumentException("searchRadiusKm must be positive, got " + searchRadiusKm);
        }
        this.queue = queue;
        this.patients = patients;
        this.resources = resources;
        this.claimer = claimer;
        this.publisher = publisher;
        this.assignments = assignments;
        this.clock = clock;
        this.searchRadiusKm = searchRadiusKm;
    }
    public Optional<Assignment> dispatchNext(){
        //1. the most urgent case, if any
        Optional<Case> next = queue.pollNext();
        if(next.isEmpty()){
            return Optional.empty();
        }
        Case c = next.get();
        // 2. where is the patient? a missing patient is a data error
        Optional<Patient> patient = patients.findById(c.patientId());
        if(patient.isEmpty()){
            queue.enqueue(c);
            throw new IllegalStateException("Patient "+ c.patientId()+ "not found for case" + c.id());
        }
        // 3 free units of resource suitable type near the patient
        List<Resource> candidates = resources.findAvailableNear(patient.get().location(),searchRadiusKm,typesFor(c.band()));

        //4 candidates arrive nearest first
        for (Resource candidate : candidates) {
            if (claimer.tryClaim(candidate.id(), c.id())) {
                candidate.transitionTo(ResourceStatus.ASSIGNED);
                resources.save(candidate);
                Assignment assignment = new Assignment(c.id(),candidate.id(),clock.instant());
                assignments.save(assignment);
                publisher.publish(new UnitAssigned(assignment.id(), c.id(), candidate.id(), clock.instant()));
                return Optional.of(assignment);
            }
        }
        queue.enqueue(c);
        return Optional.empty();

    }
    private Set<ResourceType> typesFor(RiskBand band) {
        return switch (band) {
            case CRITICAL -> Set.of(ResourceType.AMBULANCE_ALS);
            case HIGH -> Set.of(ResourceType.AMBULANCE_ALS, ResourceType.AMBULANCE_BLS);
            case MEDIUM, LOW -> Set.of(ResourceType.NURSE);
        };
    }
}

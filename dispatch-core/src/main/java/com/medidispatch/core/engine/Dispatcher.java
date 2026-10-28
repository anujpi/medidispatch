package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.port.*;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.response.ResponsePlan;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * Sends the main-response (PRIMARY) unit to the most urgent waiting case.
 * <p>
 * For each call to {@link #dispatchNext()}: take the most urgent case, ask the {@link ResponseDecider}
 * which unit types it needs (in order of preference), and for each type search in widening rings around
 * the patient ({@link SearchRings}) until a free unit can be claimed. A later type is only tried when no
 * unit of an earlier type can be had within the search radius. The claim is atomic, so two dispatchers
 * never send the same unit. If nobody can be claimed, the case goes back in the queue.
 * <p>
 * It never takes a unit from another case ({@link Preemptor}'s job) and never sends first responders
 * ({@link SupportDispatcher}'s job); {@link DispatchCoordinator} sequences all three.
 */
public class Dispatcher {
    private final CaseQueue queue;
    private final PatientRepository patients;
    private final ResourceRepository resources;
    private final ResponseDecider decider;
    private final UnitAssigner assigner;
    private final double searchRadiusKm;

    /**
     * Creates the dispatcher.
     *
     * @param queue          the queue of waiting cases
     * @param patients       where patients (and their locations) are stored
     * @param resources      where units are stored and searched by location
     * @param claimer        the atomic "this unit is mine" lock
     * @param assignments    where new trips are stored
     * @param decider        decides which unit types a case needs
     * @param publisher      where new assignments are announced
     * @param clock          source of assignment times
     * @param searchRadiusKm the furthest a unit may be from the patient, in kilometres; must be positive
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    public Dispatcher(CaseQueue queue,
                      PatientRepository patients,
                      ResourceRepository resources,
                      ResourceClaimer claimer,
                      AssignmentRepository assignments,
                      ResponseDecider decider,
                      EventPublisher publisher,
                      Clock clock,
                      double searchRadiusKm) {
        if (queue == null || patients == null || resources == null || claimer == null || assignments == null
                || decider == null || publisher == null || clock == null) {
            throw new IllegalArgumentException(
                    "queue, patients, resources, claimer, assignments, decider, publisher and clock are required");
        }
        if (searchRadiusKm <= 0) {
            throw new IllegalArgumentException("searchRadiusKm must be positive, got " + searchRadiusKm);
        }
        this.queue = queue;
        this.patients = patients;
        this.resources = resources;
        this.decider = decider;
        this.assigner = new UnitAssigner(resources, claimer, assignments, publisher, clock);
        this.searchRadiusKm = searchRadiusKm;
    }

    /**
     * Tries to send a main-response unit to the most urgent waiting case.
     * <p>
     * On success the unit is claimed and ASSIGNED, a new PRIMARY trip is saved and a {@link UnitAssigned}
     * event is published. The caller should then tell the case manager ({@code CaseManager.onAssigned}).
     *
     * @return the new trip, or empty if the queue is empty or no unit could be claimed (the case is put back)
     * @throws IllegalStateException if the case's patient is missing or its plan sends no unit;
     *                               the case is put back in the queue first
     */
    public Optional<Assignment> dispatchNext() {
        // 1. the most urgent case, if any
        Optional<Case> next = queue.pollNext();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Case c = next.get();

        // 2. where is the patient? a missing patient is a data error
        Optional<Patient> patient = patients.findById(c.patientId());
        if (patient.isEmpty()) {
            queue.enqueue(c);
            throw new IllegalStateException("Patient " + c.patientId() + " not found for case " + c.id());
        }

        // 3. what response does this case need? a queued case that needs no unit is a data error
        ResponsePlan plan = decider.decide(c, patient.get());
        if (!plan.needsUnit()) {
            queue.enqueue(c);
            throw new IllegalStateException("Case " + c.id() + " is waiting but its plan sends no unit");
        }

        // 4. try each unit type in the plan's order; a later type is only tried if no earlier one can be had
        Location where = patient.get().location();
        for (ResourceType type : plan.primaryTypes()) {
            Optional<Assignment> sent = tryTypeInRings(c, where, type);
            if (sent.isPresent()) {
                return sent;
            }
        }
        queue.enqueue(c);                                     // nobody could be claimed: the case waits
        return Optional.empty();
    }

    // one unit type, ring by ring: close first, wider only if nothing close could be claimed
    private Optional<Assignment> tryTypeInRings(Case c, Location where, ResourceType type) {
        Set<ResourceType> only = Set.of(type);
        double radius = SearchRings.first(searchRadiusKm);
        Optional<Assignment> sent = tryAssignWithin(c, where, radius, only);
        while (sent.isEmpty() && radius < searchRadiusKm) {
            radius = SearchRings.next(radius, searchRadiusKm);
            sent = tryAssignWithin(c, where, radius, only);
        }
        return sent;
    }

    // free units of these types inside this radius, nearest first: claim the first one we win
    private Optional<Assignment> tryAssignWithin(Case c, Location where, double radiusKm, Set<ResourceType> types) {
        return assigner.assignFirst(c, resources.findAvailableNear(where, radiusKm, types), AssignmentRole.PRIMARY);
    }
}

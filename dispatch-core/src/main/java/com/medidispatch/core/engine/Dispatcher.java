package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.port.*;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.response.ResponsePlan;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
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
 * Within a ring, the database returns units nearest first (straight line); the first
 * {@link #RANKED_BY_TRAVEL_TIME} are then put in order of {@link TravelTime}, so a unit that is nearer but across a
 * lake loses to one that is farther but on the right road. With the default (a straight line) the order is
 * unchanged.
 * <p>
 * It never takes a unit from another case ({@link Preemptor}'s job) and never sends first responders
 * ({@link SupportDispatcher}'s job); {@link DispatchCoordinator} sequences all three.
 */
public class Dispatcher {

    /** How many of a ring's nearest units are re-ranked by travel time (each costs a route). */
    static final int RANKED_BY_TRAVEL_TIME = 5;

    private final CaseQueue queue;
    private final PatientRepository patients;
    private final ResourceRepository resources;
    private final ResponseDecider decider;
    private final UnitAssigner assigner;
    private final double searchRadiusKm;
    private final TravelTime travel;

    /**
     * Creates the dispatcher, ranking units by straight-line travel time (that is, by distance).
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
        this(queue, patients, resources, claimer, assignments, decider, publisher, clock, searchRadiusKm,
                new StraightLineTravelTime());
    }

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
     * @param travel         how long a unit takes to reach the patient (for example by road, with traffic)
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
                      double searchRadiusKm,
                      TravelTime travel) {
        if (queue == null || patients == null || resources == null || claimer == null || assignments == null
                || decider == null || publisher == null || clock == null) {
            throw new IllegalArgumentException(
                    "queue, patients, resources, claimer, assignments, decider, publisher and clock are required");
        }
        if (searchRadiusKm <= 0) {
            throw new IllegalArgumentException("searchRadiusKm must be positive, got " + searchRadiusKm);
        }
        if (travel == null) {
            throw new IllegalArgumentException("travel is required");
        }
        this.travel = travel;
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

    // free units of these types inside this radius, soonest there first: claim the first one we win
    // ponytail: a unit just outside the ring could be faster by road; and each ranked unit is its own route (one
    // search from the patient over the reversed graph could time them all at once)
    private Optional<Assignment> tryAssignWithin(Case c, Location where, double radiusKm, Set<ResourceType> types) {
        List<Resource> nearestFirst = resources.findAvailableNear(where, radiusKm, types);
        return assigner.assignFirst(c, fastestFirst(nearestFirst, where), AssignmentRole.PRIMARY);
    }

    private record Ranked(Resource unit, double seconds, int position) {}

    /**
     * Puts the first {@link #RANKED_BY_TRAVEL_TIME} units in order of travel time to the patient (siren on); the rest
     * keep their order after them. A unit that cannot reach the patient sorts last but is still tried: better a unit
     * the road model cannot route than no unit. Ties keep the given order, so with the straight-line default the
     * order is exactly the database's (by distance, then id).
     *
     * @param nearestFirst units ordered by straight-line distance
     * @param patient      where the patient is
     * @return the order to try them in
     */
    List<Resource> fastestFirst(List<Resource> nearestFirst, Location patient) {
        int ranked = Math.min(RANKED_BY_TRAVEL_TIME, nearestFirst.size());
        List<Ranked> head = new ArrayList<>();
        for (int i = 0; i < ranked; i++) {
            Resource unit = nearestFirst.get(i);
            OptionalDouble seconds = travel.seconds(unit.location(), patient, true);
            head.add(new Ranked(unit, seconds.isPresent() ? seconds.getAsDouble() : Double.POSITIVE_INFINITY, i));
        }
        head.sort((a, b) -> {
            int bySeconds = Double.compare(a.seconds(), b.seconds());
            return bySeconds != 0 ? bySeconds : Integer.compare(a.position(), b.position());
        });
        List<Resource> order = new ArrayList<>();
        for (Ranked r : head) {
            order.add(r.unit());
        }
        order.addAll(nearestFirst.subList(ranked, nearestFirst.size()));
        return order;
    }
}

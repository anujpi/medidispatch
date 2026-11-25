package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.event.OverrideApplied;
import com.medidispatch.core.event.OverrideKind;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.CaseQueue;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.EventPublisher;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceClaimer;
import com.medidispatch.core.port.ResourceRepository;
import com.medidispatch.core.response.ResponseDecider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a human dispatcher can do over the engine's head: cancel a case, send a unit of their choice, or take a unit
 * out of service. No real service lets software dispatch unwatched; the dispatcher knows things the engine cannot
 * (a phone call saying the patient is fine, a crew radioing a flat tyre).
 * <p>
 * Each action checks everything it can before changing anything, so a refused action leaves no trace. Each one ends
 * with an {@link OverrideApplied} event carrying the dispatcher's reason.
 * <p>
 * There is no "recall this unit" on its own: the case would go back in the queue and the engine would send the same
 * unit again a second later, since it is still the nearest. {@link #sendUnit} (pick the replacement) and
 * {@link #takeOutOfService} (an offline unit is never picked) cover what a recall is for.
 */
public class OperatorOverrides {
    private static final Set<CaseStatus> CANCELLABLE =
            Set.of(CaseStatus.MONITORING, CaseStatus.ALERTED, CaseStatus.RESPONDING);
    private static final Set<ResourceType> AMBULANCES =
            Set.of(ResourceType.AMBULANCE_ALS, ResourceType.AMBULANCE_BLS);

    private final CaseRepository cases;
    private final CaseQueue queue;
    private final CaseManager caseManager;
    private final AssignmentRepository assignments;
    private final AssignmentManager assignmentManager;
    private final ResponseRecovery recovery;
    private final ResourceRepository resources;
    private final PatientRepository patients;
    private final ResponseDecider decider;
    private final UnitAssigner assigner;
    private final EventPublisher publisher;
    private final Clock clock;

    /**
     * Creates the overrides.
     *
     * @param cases             where cases are stored
     * @param queue             the waiting queue (a case that gets a unit leaves it)
     * @param caseManager       moves cases through their life cycle
     * @param assignments       where trips are stored
     * @param assignmentManager ends trips (cancelling frees the unit)
     * @param recovery          fails a trip and requeues its case, as for a breakdown
     * @param resources         where units are stored
     * @param claimer           unit claims, so a chosen unit can never be sent to two cases
     * @param patients          where patients are stored (the response plan needs the patient)
     * @param decider           which unit types a case's plan allows
     * @param publisher         announces the events
     * @param clock             the engine's clock
     * @throws IllegalArgumentException if any argument is missing
     */
    public OperatorOverrides(CaseRepository cases, CaseQueue queue, CaseManager caseManager,
                             AssignmentRepository assignments, AssignmentManager assignmentManager,
                             ResponseRecovery recovery, ResourceRepository resources, ResourceClaimer claimer,
                             PatientRepository patients, ResponseDecider decider,
                             EventPublisher publisher, Clock clock) {
        if (cases == null || queue == null || caseManager == null || assignments == null
                || assignmentManager == null || recovery == null || resources == null || claimer == null
                || patients == null || decider == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("Every part of the overrides is required");
        }
        this.cases = cases;
        this.queue = queue;
        this.caseManager = caseManager;
        this.assignments = assignments;
        this.assignmentManager = assignmentManager;
        this.recovery = recovery;
        this.resources = resources;
        this.patients = patients;
        this.decider = decider;
        this.assigner = new UnitAssigner(resources, claimer, assignments, publisher, clock);
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Calls the case off: every trip on it is cancelled (its unit is free again), and the case becomes CANCELLED and
     * leaves the queue. If the patient's readings stay bad, the next one opens a new case: the alarm comes back if
     * it was real.
     *
     * @param caseId the case
     * @param reason why, for the record
     * @return the cancelled case
     * @throws IllegalArgumentException if the reason is blank
     * @throws IllegalStateException    if there is no such case, or it is transporting, stabilised or finished
     *                                  (nothing is changed)
     */
    public Case cancelCase(String caseId, String reason) {
        requireReason(reason);
        Case c = requireCase(caseId);
        if (!CANCELLABLE.contains(c.status())) {
            throw new IllegalStateException("Case " + caseId + " is " + c.status() + " and cannot be cancelled");
        }
        for (Assignment trip : assignments.findActiveByCaseId(caseId)) {
            assignmentManager.cancel(trip.id());
        }
        caseManager.cancel(caseId);
        publisher.publish(new OverrideApplied(OverrideKind.CANCEL_CASE, caseId, null, reason, clock.instant()));
        return requireCase(caseId);
    }

    /**
     * Sends the unit the dispatcher chose as the case's main response. If another unit was already on its way, its
     * trip is cancelled and it is free again; first responders on the case are left as they are. A waiting case
     * leaves the queue and becomes RESPONDING.
     * <p>
     * Allowed units: any free ambulance (it can always carry the patient to hospital), or a unit of a type the
     * case's response plan lists. The new unit is claimed before anything else changes, so losing it to another
     * case at the last moment leaves everything as it was.
     *
     * @param caseId the case
     * @param unitId the unit to send
     * @param reason why, for the record
     * @return the new trip
     * @throws IllegalArgumentException if the reason is blank
     * @throws IllegalStateException    if the case or unit does not exist, the case is not waiting or responding,
     *                                  the unit is not free or cannot serve the case, or the current unit is
     *                                  already on scene (nothing is changed)
     */
    public Assignment sendUnit(String caseId, String unitId, String reason) {
        requireReason(reason);
        Case c = requireCase(caseId);
        if (c.status() != CaseStatus.ALERTED && c.status() != CaseStatus.RESPONDING) {
            throw new IllegalStateException("Case " + caseId + " is " + c.status()
                    + ": a unit can only be sent to a case that is waiting or responding");
        }
        Resource unit = resources.findById(unitId)
                .orElseThrow(() -> new IllegalStateException("No unit " + unitId));
        if (unit.status() != ResourceStatus.AVAILABLE) {
            throw new IllegalStateException(unitId + " is not free (" + unit.status() + ")");
        }
        Set<ResourceType> allowed = allowedTypes(c);
        if (!allowed.contains(unit.type())) {
            List<String> names = new ArrayList<>();
            for (ResourceType type : allowed) {
                names.add(type.name());
            }
            Collections.sort(names);                          // the same message every time
            throw new IllegalStateException(unitId + " (" + unit.type() + ") cannot serve a "
                    + c.band() + " case; send one of: " + String.join(", ", names));
        }
        Optional<Assignment> current = primaryOf(caseId);
        if (current.isPresent() && current.get().status() != AssignmentStatus.ASSIGNED
                && current.get().status() != AssignmentStatus.EN_ROUTE) {
            throw new IllegalStateException(current.get().resourceId() + " is already "
                    + current.get().status() + ": it cannot be replaced now");
        }

        // claim first: if another case wins this unit, nothing has changed yet
        Assignment sent = assigner.assignFirst(c, List.of(unit), AssignmentRole.PRIMARY)
                .orElseThrow(() -> new IllegalStateException(unitId + " was just taken for another case"));
        if (current.isPresent()) {
            // cancel, not recovery: the case already has its new unit and must not go back in the queue
            assignmentManager.cancel(current.get().id());
        }
        if (c.status() == CaseStatus.ALERTED) {
            queue.remove(caseId);
            caseManager.onAssigned(sent);
        }
        publisher.publish(new OverrideApplied(OverrideKind.SEND_UNIT, caseId, unitId, reason, clock.instant()));
        return sent;
    }

    /**
     * Takes a unit off duty (breakdown, crew unwell). On a trip, the trip FAILS exactly as for a breakdown the
     * engine notices itself: the unit goes offline and a main-response case goes back in the queue for the next-best
     * unit. A free unit simply goes offline. "Back in service" ({@link UnitTracker#onBackInService}) undoes it.
     *
     * @param unitId the unit
     * @param reason why, for the record
     * @return the unit, now OFFLINE
     * @throws IllegalArgumentException if the reason is blank
     * @throws IllegalStateException    if there is no such unit, it is already offline, or it is carrying a patient
     *                                  (a breakdown in transport needs its own flow, see BACKLOG; nothing is changed)
     */
    public Resource takeOutOfService(String unitId, String reason) {
        requireReason(reason);
        Resource unit = resources.findById(unitId)
                .orElseThrow(() -> new IllegalStateException("No unit " + unitId));
        Optional<Assignment> trip = assignments.findActiveByResourceId(unitId);
        if (trip.isPresent()) {
            if (trip.get().status() == AssignmentStatus.TRANSPORTING) {
                throw new IllegalStateException(unitId + " is carrying a patient and cannot be taken out of service");
            }
            recovery.onFailed(trip.get().id());
        } else {
            unit.transitionTo(ResourceStatus.OFFLINE);           // refuses an already OFFLINE unit
            resources.save(unit);
        }
        String caseId = trip.isPresent() ? trip.get().caseId() : null;
        publisher.publish(new OverrideApplied(OverrideKind.OUT_OF_SERVICE, caseId, unitId, reason, clock.instant()));
        return resources.findById(unitId).orElseThrow();
    }

    // any ambulance, plus whatever the case's plan would send itself (a nurse for a MEDIUM case)
    private Set<ResourceType> allowedTypes(Case c) {
        Patient patient = patients.findById(c.patientId())
                .orElseThrow(() -> new IllegalStateException("Patient " + c.patientId() + " not found"));
        Set<ResourceType> allowed = new HashSet<>(AMBULANCES);
        allowed.addAll(decider.decide(c, patient).primaryTypes());
        return allowed;
    }

    private Optional<Assignment> primaryOf(String caseId) {
        for (Assignment a : assignments.findActiveByCaseId(caseId)) {
            if (a.role() == AssignmentRole.PRIMARY) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private Case requireCase(String caseId) {
        return cases.findById(caseId).orElseThrow(() -> new IllegalStateException("No case " + caseId));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }
}

package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.ResourceRepository;

import java.util.Set;

/**
 * Starts the second leg of a trip: the crew on scene takes the patient to a hospital.
 * <p>
 * One call does it all: checks the trip may transport, chooses the hospital ({@link DestinationChooser}) from where
 * the unit is now, moves the case to TRANSPORTING and the trip to TRANSPORTING with that hospital and the needs. Every check runs
 * before anything changes, so a refusal leaves the trip and the case exactly as they were. Finishing the transport
 * is the usual {@link AssignmentManager#complete(String)}, then stabilise and close the case.
 */
public class TransportCoordinator {

    private final AssignmentRepository assignments;
    private final ResourceRepository resources;
    private final DestinationChooser chooser;
    private final AssignmentManager assignmentManager;
    private final CaseManager caseManager;

    /**
     * @param assignments       where trips are stored
     * @param resources         where units are stored (for the unit's type and position)
     * @param chooser           picks the hospital
     * @param assignmentManager moves the trip
     * @param caseManager       moves the case
     * @throws IllegalArgumentException if any argument is missing
     */
    public TransportCoordinator(AssignmentRepository assignments, ResourceRepository resources,
                                DestinationChooser chooser, AssignmentManager assignmentManager,
                                CaseManager caseManager) {
        if (assignments == null || resources == null || chooser == null
                || assignmentManager == null || caseManager == null) {
            throw new IllegalArgumentException(
                    "assignments, resources, chooser, assignmentManager and caseManager are required");
        }
        this.assignments = assignments;
        this.resources = resources;
        this.chooser = chooser;
        this.assignmentManager = assignmentManager;
        this.caseManager = caseManager;
    }

    /**
     * The crew takes the patient to hospital.
     *
     * @param tripId the main trip of the case; must be ARRIVED
     * @param needed the specialist services the patient needs, as judged by the crew; empty means any emergency
     *               department
     * @return the updated trip, TRANSPORTING, with its hospital
     * @throws IllegalArgumentException if {@code tripId} is blank or {@code needed} is missing
     * @throws IllegalStateException    if there is no such trip, it is not the main (PRIMARY) trip, it is not
     *                                  ARRIVED, the unit is not an ambulance, the case is not RESPONDING, or no
     *                                  hospital is registered; nothing is changed
     */
    public Assignment start(String tripId, Set<Capability> needed) {
        if (tripId == null || tripId.isBlank()) {
            throw new IllegalArgumentException("tripId is required");
        }
        if (needed == null) {
            throw new IllegalArgumentException("needed is required (empty means any emergency department)");
        }
        Assignment trip = assignments.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("No assignment " + tripId));
        if (trip.role() != AssignmentRole.PRIMARY) {
            throw new IllegalStateException("Only the main unit transports; trip " + tripId + " is " + trip.role());
        }
        if (trip.status() != AssignmentStatus.ARRIVED) {
            throw new IllegalStateException("Trip " + tripId + " is " + trip.status() + ", not ARRIVED");
        }
        Resource unit = resources.findById(trip.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + trip.resourceId() + " not found"));
        if (!unit.type().canCarryPatient()) {
            throw new IllegalStateException("A " + unit.type() + " cannot carry a patient");
        }
        Facility hospital = chooser.choose(unit.location(), needed)
                .orElseThrow(() -> new IllegalStateException("No hospital is registered"));
        // The case first: it is the only step that can still fail, and then the trip is untouched. The trip was
        // checked above, so moving it cannot fail.
        caseManager.onTransporting(trip.caseId());
        return assignmentManager.startTransport(tripId, hospital.id(), needed);
    }
}

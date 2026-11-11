package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.port.AssignmentRepository;
import com.medidispatch.core.port.PatientRepository;
import com.medidispatch.core.port.ResourceRepository;
import com.medidispatch.core.response.ResponseDecider;
import com.medidispatch.core.response.ResponsePlan;

/**
 * Makes sure the right unit is coming when a patient gets worse while help is already on the way.
 * <p>
 * A response is chosen when the case opens. If the patient then gets worse (for example MEDIUM, then CRITICAL a
 * minute later), the unit that was sent may no longer be enough: a nurse for a critical patient. In that case the
 * unit's trip becomes a first responder's (PRIMARY to SUPPORT) and keeps going, and the case goes back in the
 * queue so the dispatch loop sends a proper unit. When that unit arrives, the first responder stands down as usual.
 * <p>
 * It only acts when the band went <em>up</em>. A patient who improves keeps the stronger unit: replacing an
 * ambulance with a nurse halfway would only slow things down.
 */
public class ResponseUpgrader {
    private final AssignmentRepository assignments;
    private final ResourceRepository resources;
    private final PatientRepository patients;
    private final ResponseDecider decider;
    private final AssignmentManager assignmentManager;
    private final CaseManager caseManager;

    /**
     * Creates the upgrader.
     *
     * @param assignments       where trips are stored (to find the case's main response)
     * @param resources         where units are stored (to know what kind of unit is coming)
     * @param patients          where the patient comes from (for the response decision)
     * @param decider           decides which units the case needs now
     * @param assignmentManager turns the trip into a first responder's
     * @param caseManager       puts the case back in the queue for a better unit
     * @throws IllegalArgumentException if any argument is missing
     */
    public ResponseUpgrader(AssignmentRepository assignments,
                            ResourceRepository resources,
                            PatientRepository patients,
                            ResponseDecider decider,
                            AssignmentManager assignmentManager,
                            CaseManager caseManager) {
        if (assignments == null || resources == null || patients == null || decider == null
                || assignmentManager == null || caseManager == null) {
            throw new IllegalArgumentException(
                    "assignments, resources, patients, decider, assignmentManager and caseManager are required");
        }
        this.assignments = assignments;
        this.resources = resources;
        this.patients = patients;
        this.decider = decider;
        this.assignmentManager = assignmentManager;
        this.caseManager = caseManager;
    }

    /**
     * Asks for a better unit if the case got worse and the unit on its way is no longer enough.
     *
     * @param c            the case, with its new band
     * @param previousBand the band the case had before this change
     * @return {@code true} if the current unit became a first responder and the case was queued for a better
     *         unit; {@code false} if nothing needed to change
     * @throws IllegalArgumentException if an argument is missing
     * @throws IllegalStateException    if the patient or the unit on the case's main trip is missing
     */
    public boolean upgradeIfWorse(Case c, RiskBand previousBand) {
        if (c == null || previousBand == null) {
            throw new IllegalArgumentException("case and previousBand are required");
        }
        // only a case that got worse while help is on the way
        if (c.band().compareTo(previousBand) <= 0 || c.status() != CaseStatus.RESPONDING) {
            return false;
        }
        Assignment primary = null;
        for (Assignment a : assignments.findActiveByCaseId(c.id())) {
            if (a.role() == AssignmentRole.PRIMARY) {
                primary = a;
            }
        }
        if (primary == null) {
            return false;
        }
        String unitId = primary.resourceId();
        Patient patient = patients.findById(c.patientId())
                .orElseThrow(() -> new IllegalStateException("Patient " + c.patientId() + " not found"));
        Resource unit = resources.findById(unitId)
                .orElseThrow(() -> new IllegalStateException("Unit " + unitId + " not found"));

        ResponsePlan plan = decider.decide(c, patient);
        if (plan.primaryTypes().contains(unit.type())) {
            return false;                                     // the unit coming is still a right one
        }
        assignmentManager.demoteToSupport(primary.id());      // keeps going, now as a first responder
        caseManager.onAssignmentLost(c.id());                 // back in the queue, keeping its place
        return true;
    }
}

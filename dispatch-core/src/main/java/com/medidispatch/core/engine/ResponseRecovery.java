package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;

/**
 * The single entry point for "this unit is lost or taken".
 * <p>
 * Each method ends the trip first (which releases the unit or takes it offline). Then, if the trip was the
 * case's main response ({@link AssignmentRole#PRIMARY}), it puts the case back in the queue so the next-best
 * unit is sent. Losing a first responder ({@link AssignmentRole#SUPPORT}) ends only that trip: the main
 * response is still coming, so the case stays as it is. Callers cannot forget the second step.
 * Known limit: the two steps are not atomic; real transactions come with the database.
 */
public class ResponseRecovery {
    private final AssignmentManager assignmentManager;
    private final CaseManager caseManager;

    /**
     * Creates the recovery.
     *
     * @param assignmentManager ends the trip
     * @param caseManager       requeues the case
     * @throws IllegalArgumentException if any argument is missing
     */
    public ResponseRecovery(AssignmentManager assignmentManager, CaseManager caseManager) {
        if(assignmentManager == null || caseManager == null){
            throw new IllegalArgumentException("Assignment Manager and Case Manager are required");
        }
        this.assignmentManager = assignmentManager;
        this.caseManager = caseManager;
    }

    /**
     * The unit could not complete (breakdown, lost signal, stalled): the trip FAILS, the unit goes offline,
     * and, for a main-response trip, the case is requeued.
     *
     * @param assignmentId the trip whose unit failed
     * @throws IllegalStateException if there is no such trip or it has already ended; nothing is changed
     */
    public void onFailed(String assignmentId){
        afterTripLost(assignmentManager.onFailed(assignmentId));
    }

    /**
     * The unit never acknowledged: the trip TIMES OUT, the unit goes offline, and, for a main-response trip,
     * the case is requeued.
     *
     * @param assignmentId the trip whose unit never answered
     * @throws IllegalStateException if there is no such trip or it is no longer ASSIGNED; nothing is changed
     */
    public void onTimedOut(String assignmentId){
        afterTripLost(assignmentManager.timeOut(assignmentId));
    }

    /**
     * The unit was taken for a worse case: the trip is CANCELLED, the healthy unit becomes available,
     * and, for a main-response trip, the case is requeued.
     *
     * @param assignmentId the trip that lost its unit
     * @throws IllegalStateException if there is no such trip or it has already ended; nothing is changed
     */
    public void onPreempted(String assignmentId){
        afterTripLost(assignmentManager.cancel(assignmentId));
    }

    // the trip has ended: only a lost main response leaves the case waiting for another unit
    private void afterTripLost(Assignment ended) {
        if (ended.role() == AssignmentRole.PRIMARY) {
            caseManager.onAssignmentLost(ended.caseId());
        }
    }
}

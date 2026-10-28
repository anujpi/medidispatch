package com.medidispatch.core.engine;

/**
 * The single entry point for "this case lost its unit".
 * <p>
 * Each method does the same two steps in the same order: end the trip (which releases or takes the unit
 * offline), then put the case back in the queue so the next-best unit is sent. Callers cannot forget the
 * second step. Known limit: the two steps are not atomic; real transactions come with the database.
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
     * and the case is requeued.
     *
     * @param caseId the case whose unit failed
     * @throws IllegalStateException if the case has no active trip; nothing is changed
     */
    public void onFailed(String caseId){
        assignmentManager.onFailed(caseId);
        caseManager.onAssignmentLost(caseId);
    }
    /**
     * The unit never acknowledged: the trip TIMES OUT, the unit goes offline, and the case is requeued.
     *
     * @param caseId the case whose unit never answered
     * @throws IllegalStateException if the case has no active ASSIGNED trip; nothing is changed
     */
    public void onTimedOut(String caseId){
        assignmentManager.timeOut(caseId);
        caseManager.onAssignmentLost(caseId);
    }
    /**
     * The unit was taken for a worse case: the trip is CANCELLED, the healthy unit becomes available,
     * and the case is requeued.
     *
     * @param caseId the case that lost its unit
     * @throws IllegalStateException if the case has no active trip; nothing is changed
     */
    public void onPreempted(String caseId){
        assignmentManager.cancel(caseId);
        caseManager.onAssignmentLost(caseId);
    }
}

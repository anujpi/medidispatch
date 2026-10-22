package com.medidispatch.core.engine;

public class ResponseRecovery {
    private final AssignmentManager assignmentManager;
    private final CaseManager caseManager;

    public ResponseRecovery(AssignmentManager assignmentManager, CaseManager caseManager) {
        if(assignmentManager == null || caseManager == null){
            throw new IllegalArgumentException("Assignment Manager and Case Manager are required");
        }
        this.assignmentManager = assignmentManager;
        this.caseManager = caseManager;
    }
    //unit breakdown
    public void onFailed(String caseId){
        assignmentManager.onFailed(caseId);
        caseManager.onAssignmentLost(caseId);
    }
    // the unit never answered
    public void onTimedOut(String caseId){
        assignmentManager.timeOut(caseId);
        caseManager.onAssignmentLost(caseId);
    }
}

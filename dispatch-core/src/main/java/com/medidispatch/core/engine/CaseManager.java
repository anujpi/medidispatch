package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.port.CaseQueue;
import com.medidispatch.core.port.CaseRepository;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public class CaseManager {
    private final CaseRepository cases;
    private final CaseQueue queue;
    private final Clock clock;

    public CaseManager(CaseRepository cases, CaseQueue queue, Clock clock) {
        if (cases == null || queue == null || clock == null) {
            throw new IllegalArgumentException("cases, queue and clock are required");
        }
        this.cases = cases;
        this.queue = queue;
        this.clock = clock;
    }
    public Optional<Case> handle(RiskAssessment assessment){
        if (assessment == null) {
            throw new IllegalArgumentException("assessment is required");
        }
        RiskBand band = assessment.band();
        Optional<Case> existing = cases.findOpenByPatientId(assessment.patientId());
        if(existing.isEmpty()) {
            if (!needsAlert(band)) {
                return Optional.empty();
            }
            Case c = new Case(UUID.randomUUID().toString(), assessment.patientId(), band, clock.instant());
            c.transitionTo(CaseStatus.ALERTED);
            cases.save(c);
            queue.enqueue(c);
            return Optional.of(c);
        }
        Case c = existing.get();
        c.updateBand(band);
        switch (c.status()){
            case MONITORING -> {
                if(needsAlert(band)){
                    c.transitionTo(CaseStatus.ALERTED);
                    queue.enqueue(c);
                }
            }
            case ALERTED -> {
                if(needsAlert(band)){
                    queue.enqueue(c);
                }else{
                    c.transitionTo(CaseStatus.MONITORING);
                    queue.remove(c.id());
                }
            }
            default -> { }
        }
        cases.save(c);
        return Optional.of(c);
    }
    public void onAssigned(Assignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment is required");
        }
        move(assignment.caseId(), CaseStatus.RESPONDING);
    }
    public void onStabilised(String caseId) {
        move(caseId, CaseStatus.STABILISED);
    }

    public void close(String caseId) {
        move(caseId, CaseStatus.CLOSED);
    }

    private void move(String caseId, CaseStatus next) {
        Case c = cases.findById(caseId)
                .orElseThrow(() -> new IllegalStateException("Case " + caseId + " not found"));
        c.transitionTo(next);
        cases.save(c);
    }

    private boolean needsAlert(RiskBand band) {
        return band.compareTo(RiskBand.MEDIUM) >= 0;
    }
}

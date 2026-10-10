package com.medidispatch.core.domain;

import java.time.Instant;

public class Case {
    private final String id;
    private final String patientId;
    private CaseStatus status;
    private RiskBand band;
    private final Instant openedAt;

    public Case(String id,String patientId,RiskBand band,Instant openedAt){
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId is required");
        }
        if (band == null) {
            throw new IllegalArgumentException("band is required");
        }
        if (openedAt == null) {
            throw new IllegalArgumentException("openedAt is required");
        }
        this.id = id;
        this.patientId = patientId;
        this.band = band;
        this.openedAt = openedAt;
        this.status = CaseStatus.MONITORING;
    }

    void transitionTo(CaseStatus next){
        if (next == null) {
            throw new IllegalArgumentException("next status is required");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot go from " + status + " to " + next);
        }
        status = next;
    }
    void updateBand(RiskBand newBand){
        if (newBand == null) {
            throw new IllegalArgumentException("band is required");
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("Case " + id + " is " + status + ", its band can't change");
        }
        band = newBand;
    }
    public String id()          { return id; }
    public String patientId()   { return patientId; }
    public CaseStatus status()  { return status; }
    public RiskBand band()      { return band; }
    public Instant openedAt()   { return openedAt; }

}

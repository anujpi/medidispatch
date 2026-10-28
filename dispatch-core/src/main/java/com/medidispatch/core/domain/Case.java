package com.medidispatch.core.domain;

import java.time.Instant;

/**
 * One episode of a patient needing attention, from the first alert until it is closed.
 * <p>
 * A patient has at most one open case at a time. The case carries the current {@link RiskBand}, which
 * can change while it is open (the patient gets worse or better), and its {@link CaseStatus}. Status
 * changes go through {@link #transitionTo(CaseStatus)}, which refuses illegal moves.
 */
public class Case {
    private final String id;
    private final String patientId;
    private CaseStatus status;
    private RiskBand band;
    private final Instant openedAt;

    /**
     * Creates a case in {@link CaseStatus#MONITORING}.
     *
     * @param id        unique case id; never blank
     * @param patientId the patient this case is about; never blank
     * @param band      the starting urgency
     * @param openedAt  when the case was opened; among cases of the same band, older ones are served first
     * @throws IllegalArgumentException if an id is blank or any argument is missing
     */
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

    /**
     * Moves the case to a new status, if the move is allowed.
     *
     * @param next the status to move to
     * @throws IllegalArgumentException if {@code next} is missing
     * @throws IllegalStateException    if the move is not allowed; the status is left unchanged
     * @see CaseStatus#canTransitionTo(CaseStatus)
     */
    public void transitionTo(CaseStatus next){
        if (next == null) {
            throw new IllegalArgumentException("next status is required");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot go from " + status + " to " + next);
        }
        status = next;
    }
    /**
     * Changes the urgency band. Allowed while the case is open, including while a unit is responding.
     *
     * @param newBand the new band
     * @throws IllegalArgumentException if {@code newBand} is missing
     * @throws IllegalStateException    if the case is already closed or cancelled
     */
    public void updateBand(RiskBand newBand){
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

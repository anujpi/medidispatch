package com.medidispatch.core.engine;

import com.medidispatch.core.domain.*;
import com.medidispatch.core.event.CaseBandChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.port.CaseQueue;
import com.medidispatch.core.port.CaseRepository;
import com.medidispatch.core.port.EventPublisher;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns risk assessments into cases and keeps each case's status and place in the queue correct.
 * <p>
 * A patient at {@link RiskBand#MEDIUM} or worse gets an ALERTED case in the queue; a patient who recovers
 * before help is sent goes back to MONITORING and leaves the queue; a band change re-ranks a waiting case.
 * While a unit is responding, only the band is updated. Every change is saved first, then the events
 * collected along the way are published, and nothing is published when nothing changed.
 */
public class CaseManager {
    private final CaseRepository cases;
    private final CaseQueue queue;
    private final EventPublisher publisher;
    private final Clock clock;

    /**
     * Creates the manager.
     *
     * @param cases     where cases are stored
     * @param queue     the queue of cases waiting for a unit
     * @param publisher where changes are announced
     * @param clock     source of case-opening and event times
     * @throws IllegalArgumentException if any argument is missing
     */
    public CaseManager(CaseRepository cases, CaseQueue queue, EventPublisher publisher, Clock clock) {
        if (cases == null || queue == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("cases, queue, publisher and clock are required");
        }
        this.cases = cases;
        this.queue = queue;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Applies a new risk assessment to the patient's case.
     * <ul>
     *   <li>No open case and MEDIUM or worse: opens an ALERTED case and queues it.</li>
     *   <li>No open case and LOW: nothing happens.</li>
     *   <li>MONITORING and now MEDIUM or worse: alerts the case and queues it.</li>
     *   <li>ALERTED: re-queues it with the new band, or back to MONITORING and out of the queue if LOW.</li>
     *   <li>RESPONDING or STABILISED: only the band is updated.</li>
     * </ul>
     *
     * @param assessment the latest assessment
     * @return the patient's case, or empty if there is none and the patient does not need one
     * @throws IllegalArgumentException if {@code assessment} is missing
     */
    public Optional<Case> handle(RiskAssessment assessment) {
        if (assessment == null) {
            throw new IllegalArgumentException("assessment is required");
        }
        RiskBand band = assessment.band();
        List<DomainEvent> events = new ArrayList<>();
        Optional<Case> existing = cases.findOpenByPatientId(assessment.patientId());

        if (existing.isEmpty()) {
            if (!needsAlert(band)) {
                return Optional.empty();
            }
            Case c = new Case(UUID.randomUUID().toString(), assessment.patientId(), band, clock.instant());
            changeStatus(c, CaseStatus.ALERTED, events);
            cases.save(c);
            queue.enqueue(c);
            publishAll(events);
            return Optional.of(c);
        }

        Case c = existing.get();
        changeBand(c, band, events);
        switch (c.status()) {
            case MONITORING -> {
                if (needsAlert(band)) {
                    changeStatus(c, CaseStatus.ALERTED, events);
                    queue.enqueue(c);
                }
            }
            case ALERTED -> {
                if (needsAlert(band)) {
                    queue.enqueue(c);
                } else {
                    changeStatus(c, CaseStatus.MONITORING, events);
                    queue.remove(c.id());
                }
            }
            default -> { }
        }
        cases.save(c);
        publishAll(events);
        return Optional.of(c);
    }

    /**
     * A unit was assigned to the case: ALERTED to RESPONDING. Call it with every new main-response (PRIMARY)
     * assignment; a first responder does not change the case's status.
     *
     * @param assignment the new trip
     * @throws IllegalArgumentException if {@code assignment} is missing
     * @throws IllegalStateException    if the case does not exist or the move is not allowed
     */
    public void onAssigned(Assignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment is required");
        }
        move(assignment.caseId(), CaseStatus.RESPONDING);
    }

    /**
     * The patient was stabilised on scene: RESPONDING to STABILISED.
     *
     * @param caseId the case
     * @throws IllegalStateException if the case does not exist or the move is not allowed
     */
    public void onStabilised(String caseId) {
        move(caseId, CaseStatus.STABILISED);
    }

    /**
     * The episode is over: STABILISED to CLOSED. The patient can get a new case later.
     *
     * @param caseId the case
     * @throws IllegalStateException if the case does not exist or the move is not allowed
     */
    public void close(String caseId) {
        move(caseId, CaseStatus.CLOSED);
    }
    /**
     * The case lost its unit (timeout, failure or preemption): RESPONDING to ALERTED, and back in the queue
     * so another unit is sent. The case keeps its original opening time, so it keeps its place in line.
     * Normally called through {@link ResponseRecovery}, which ends the trip first.
     *
     * @param caseId the case
     * @throws IllegalStateException if the case does not exist or the move is not allowed
     */
    public void onAssignmentLost(String caseId){
        Case c = cases.findById(caseId).orElseThrow(()-> new IllegalStateException("Case" + caseId + " not found"));
        List<DomainEvent> events  = new ArrayList<>();
        changeStatus(c,CaseStatus.ALERTED,events);
        cases.save(c);
        queue.enqueue(c);
        publishAll(events);
    }

    private void move(String caseId, CaseStatus next) {
        Case c = cases.findById(caseId)
                .orElseThrow(() -> new IllegalStateException("Case " + caseId + " not found"));
        List<DomainEvent> events = new ArrayList<>();
        changeStatus(c, next, events);
        cases.save(c);
        publishAll(events);
    }

    // changes the status and records the event; the event is published later, after everything is saved
    private void changeStatus(Case c, CaseStatus next, List<DomainEvent> events) {
        CaseStatus from = c.status();
        c.transitionTo(next);
        events.add(new CaseStatusChanged(c.id(), c.patientId(), from, next, clock.instant()));
    }

    private void changeBand(Case c, RiskBand next, List<DomainEvent> events) {
        RiskBand from = c.band();
        if (from == next) {
            return;                                   // nothing changed, so there is nothing to announce
        }
        c.updateBand(next);
        events.add(new CaseBandChanged(c.id(), c.patientId(), from, next, clock.instant()));
    }

    private void publishAll(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            publisher.publish(event);
        }
    }

    private boolean needsAlert(RiskBand band) {
        return band.compareTo(RiskBand.MEDIUM) >= 0;
    }
}
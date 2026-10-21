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

public class CaseManager {
    private final CaseRepository cases;
    private final CaseQueue queue;
    private final EventPublisher publisher;
    private final Clock clock;

    public CaseManager(CaseRepository cases, CaseQueue queue, EventPublisher publisher, Clock clock) {
        if (cases == null || queue == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("cases, queue, publisher and clock are required");
        }
        this.cases = cases;
        this.queue = queue;
        this.publisher = publisher;
        this.clock = clock;
    }

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
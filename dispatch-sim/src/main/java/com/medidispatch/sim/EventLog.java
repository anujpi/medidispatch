package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.AssignmentRoleChanged;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.CaseBandChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.port.CaseRepository;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Turns the engine's events into short readable lines for the simulator window, for example
 * {@code "08:23:10  als-2 sent to p17"}, and keeps only the latest ones.
 * <p>
 * Routine events (a crew answering, a case moving to RESPONDING, a patient improving) are left out, so the log
 * shows what a dispatcher would want to see.
 */
final class EventLog {

    /** How many lines are kept. */
    static final int SIZE = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final CaseRepository cases;
    private final Deque<String> lines = new ArrayDeque<>();

    /**
     * Creates an empty log.
     *
     * @param cases where cases are stored, to show patient ids instead of case ids
     */
    EventLog(CaseRepository cases) {
        this.cases = cases;
    }

    /**
     * Adds a line for the event, if it is worth showing.
     *
     * @param event what the engine announced
     */
    void record(DomainEvent event) {
        String text = describe(event);
        if (text == null) {
            return;
        }
        lines.addFirst(TIME.format(event.occurredAt()) + "  " + text);
        if (lines.size() > SIZE) {
            lines.removeLast();                               // forget the oldest
        }
    }

    /**
     * The latest lines.
     *
     * @return at most {@link #SIZE} lines, newest first
     */
    List<String> lines() {
        return List.copyOf(lines);
    }

    // the readable text for one event, or null to leave it out
    private String describe(DomainEvent event) {
        if (event instanceof UnitAssigned e) {
            String who = e.resourceId() + " sent to " + patientOf(e.caseId());
            return e.role() == AssignmentRole.SUPPORT ? who + " as first responder" : who;
        }
        if (event instanceof AssignmentRoleChanged e) {
            return e.resourceId() + " now first responder (" + patientOf(e.caseId()) + " got worse)";
        }
        if (event instanceof AssignmentStatusChanged e) {
            String where = patientOf(e.caseId());
            return switch (e.to()) {
                case ARRIVED -> e.resourceId() + " arrived at " + where;
                case TRANSPORTING -> e.resourceId() + " taking " + where + " to hospital";
                case DONE -> e.resourceId() + " finished at " + where;
                case CANCELLED -> e.resourceId() + " called off (" + where + ")";
                case TIMED_OUT -> e.resourceId() + " never answered (" + where + ")";
                case FAILED -> e.resourceId() + " FAILED on the way to " + where;
                case ASSIGNED, EN_ROUTE -> null;
            };
        }
        if (event instanceof CaseStatusChanged e) {
            if (e.to() == CaseStatus.ALERTED && e.from() == CaseStatus.MONITORING) {
                return e.patientId() + " needs help";
            }
            if (e.to() == CaseStatus.ALERTED) {
                return e.patientId() + " waiting for another unit";
            }
            if (e.to() == CaseStatus.CLOSED) {
                return e.patientId() + " case closed";
            }
            return null;
        }
        if (event instanceof CaseBandChanged e && e.to().compareTo(e.from()) > 0 && e.to() != RiskBand.LOW) {
            return e.patientId() + " worse: " + e.from() + " to " + e.to();
        }
        return null;
    }

    // the patient a case is about, or a shortened case id if the case cannot be found
    private String patientOf(String caseId) {
        return cases.findById(caseId).map(c -> c.patientId())
                .orElse("case " + caseId.substring(0, Math.min(6, caseId.length())));
    }
}

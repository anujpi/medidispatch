package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.AssignmentRoleChanged;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.CaseBandChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class EventLogTest {

    private static final Instant AT = Instant.parse("2026-01-05T08:23:10Z");

    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final EventLog log = new EventLog(cases);

    private String caseFor(String patientId) {
        Case c = new Case("case-" + patientId, patientId, RiskBand.CRITICAL, AT);
        cases.save(c);
        return c.id();
    }

    private AssignmentStatusChanged trip(String caseId, AssignmentStatus to) {
        return new AssignmentStatusChanged("trip-1", caseId, "als-2", AssignmentStatus.EN_ROUTE, to, AT);
    }

    @Test
    void sendingAUnitShowsTheTimeTheUnitAndThePatient() {
        log.record(new UnitAssigned("trip-1", caseFor("p17"), "als-2", AssignmentRole.PRIMARY, AT));

        assertEquals("08:23:10  als-2 sent to p17", log.lines().get(0));
    }

    @Test
    void aFirstResponderAndAnUpgradeAreSaidPlainly() {
        String caseId = caseFor("p17");
        log.record(new UnitAssigned("trip-2", caseId, "doctor-1", AssignmentRole.SUPPORT, AT));
        log.record(new AssignmentRoleChanged("trip-3", caseId, "nurse-6",
                AssignmentRole.PRIMARY, AssignmentRole.SUPPORT, AT));

        assertEquals("08:23:10  nurse-6 now first responder (p17 got worse)", log.lines().get(0));
        assertEquals("08:23:10  doctor-1 sent to p17 as first responder", log.lines().get(1));
    }

    @Test
    void tripEndingsAreShownButRoutineStepsAreNot() {
        String caseId = caseFor("p17");
        log.record(trip(caseId, AssignmentStatus.EN_ROUTE));               // routine: left out
        log.record(trip(caseId, AssignmentStatus.FAILED));
        log.record(trip(caseId, AssignmentStatus.TIMED_OUT));

        assertEquals(2, log.lines().size());
        assertTrue(log.lines().get(0).endsWith("als-2 never answered (p17)"));
        assertTrue(log.lines().get(1).endsWith("als-2 FAILED on the way to p17"));
    }

    @Test
    void casesNeedingHelpGettingWorseAndClosingAreShown() {
        log.record(new CaseStatusChanged("c1", "p17", CaseStatus.MONITORING, CaseStatus.ALERTED, AT));
        log.record(new CaseBandChanged("c1", "p17", RiskBand.MEDIUM, RiskBand.CRITICAL, AT));
        log.record(new CaseBandChanged("c1", "p17", RiskBand.CRITICAL, RiskBand.LOW, AT));   // better: left out
        log.record(new CaseStatusChanged("c1", "p17", CaseStatus.RESPONDING, CaseStatus.ALERTED, AT));
        log.record(new CaseStatusChanged("c1", "p17", CaseStatus.STABILISED, CaseStatus.CLOSED, AT));

        assertEquals(4, log.lines().size());
        assertTrue(log.lines().get(0).endsWith("p17 case closed"));
        assertTrue(log.lines().get(1).endsWith("p17 waiting for another unit"));
        assertTrue(log.lines().get(2).endsWith("p17 worse: MEDIUM to CRITICAL"));
        assertTrue(log.lines().get(3).endsWith("p17 needs help"));
    }

    @Test
    void onlyTheLatestLinesAreKeptNewestFirst() {
        String caseId = caseFor("p17");
        for (int i = 0; i < EventLog.SIZE + 5; i++) {
            log.record(new UnitAssigned("trip-" + i, caseId, "als-" + i, AssignmentRole.PRIMARY, AT));
        }

        assertEquals(EventLog.SIZE, log.lines().size());
        assertTrue(log.lines().get(0).contains("als-" + (EventLog.SIZE + 4)));   // the newest
    }

    @Test
    void anUnknownCaseFallsBackToAShortCaseId() {
        log.record(new UnitAssigned("trip-1", "abcdef123456", "als-2", AssignmentRole.PRIMARY, AT));

        assertTrue(log.lines().get(0).endsWith("als-2 sent to case abcdef"));
    }
}

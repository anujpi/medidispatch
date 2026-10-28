package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Patient;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.UnitAssigned;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryPatientRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import com.medidispatch.core.response.LadderResponseDecider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SupportDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    // every patient is at the origin; units sit at increasing distances north of it
    private static final Location ORIGIN = new Location(0, 0);
    private static final Location NEAR = new Location(0.01, 0);       // about 1.1 km
    private static final Location MID = new Location(0.05, 0);        // about 5.6 km
    private static final Location FAR = new Location(0.1, 0);         // about 11.1 km
    private static final Location FARTHER = new Location(0.15, 0);    // about 16.7 km

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryPatientRepository patients = new InMemoryPatientRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final Dispatcher dispatcher = new Dispatcher(queue, patients, resources, claimer, assignments,
            new LadderResponseDecider(), events, clock, 20.0);
    private final SupportDispatcher support = new SupportDispatcher(cases, patients, resources, claimer,
            assignments, new LadderResponseDecider(), events, clock);

    // ---------- helpers ----------

    private void addUnit(String id, ResourceType type, Location where) {
        resources.save(new Resource(id, type, where));
    }

    private Case openCase(String patientId, RiskBand band) {
        patients.save(new Patient(patientId, ORIGIN));
        RiskAssessment a = new RiskAssessment(patientId, 0, band, List.of(), NOW);
        return caseManager.handle(a).get();
    }

    // the ambulance is sent first, the way the coordinator does it
    private Assignment ambulanceSent() {
        Assignment a = dispatcher.dispatchNext().get();
        caseManager.onAssigned(a);
        return a;
    }

    private ResourceStatus unitStatus(String id) {
        return resources.findById(id).get().status();
    }

    // ---------- who is sent ----------

    @Test
    void aFirstResponderCloserThanTheAmbulanceIsSent() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        Case c = openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();
        events.clear();

        Assignment nurse = support.sendSupportFor(ambulance).get();

        assertEquals("nurse-1", nurse.resourceId());
        assertEquals(c.id(), nurse.caseId());
        assertEquals(AssignmentRole.SUPPORT, nurse.role());
        assertEquals(AssignmentStatus.ASSIGNED, nurse.status());
        assertEquals(ResourceStatus.ASSIGNED, unitStatus("nurse-1"));
        assertEquals(c.id(), claimer.claimedBy("nurse-1").get());
        assertEquals(List.of(new UnitAssigned(nurse.id(), c.id(), "nurse-1", AssignmentRole.SUPPORT, NOW)),
                events.published());
    }

    @Test
    void aDoctorIsPreferredOverANurseWhenBothAreCloserThanTheAmbulance() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        addUnit("doctor-1", ResourceType.DOCTOR, MID);
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();

        assertEquals("doctor-1", support.sendSupportFor(ambulance).get().resourceId());

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("nurse-1"));
    }

    @Test
    void aNurseIsSentWhenNoDoctorIsCloserThanTheAmbulance() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("doctor-1", ResourceType.DOCTOR, FARTHER);           // would arrive after the ambulance
        addUnit("nurse-1", ResourceType.NURSE, MID);
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();

        assertEquals("nurse-1", support.sendSupportFor(ambulance).get().resourceId());

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("doctor-1"));
    }

    @Test
    void nobodyIsSentWhenTheAmbulanceIsCloserThanEveryResponder() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, NEAR);
        addUnit("nurse-1", ResourceType.NURSE, MID);
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();

        assertTrue(support.sendSupportFor(ambulance).isEmpty());

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("nurse-1"));
    }

    @Test
    void aResponderAlreadyBusyOrClaimedIsSkipped() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        addUnit("nurse-2", ResourceType.NURSE, MID);
        claimer.tryClaim("nurse-1", "another-case");                // someone else holds the nearest nurse
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();

        assertEquals("nurse-2", support.sendSupportFor(ambulance).get().resourceId());
    }

    // ---------- when nobody is sent ----------

    @Test
    void onlyCasesWhosePlanAsksForItGetAFirstResponder() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        openCase("p1", RiskBand.HIGH);                                // the ladder gives HIGH no first responder
        Assignment ambulance = ambulanceSent();

        assertTrue(support.sendSupportFor(ambulance).isEmpty());

        assertEquals(ResourceStatus.AVAILABLE, unitStatus("nurse-1"));
    }

    @Test
    void aCaseNeverGetsTwoFirstResponders() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        addUnit("nurse-1", ResourceType.NURSE, NEAR);
        addUnit("nurse-2", ResourceType.NURSE, MID);
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();
        support.sendSupportFor(ambulance);

        Optional<Assignment> again = support.sendSupportFor(ambulance);

        assertTrue(again.isEmpty());
        assertEquals(ResourceStatus.AVAILABLE, unitStatus("nurse-2"));
    }

    @Test
    void findingNobodyChangesNothingAndAnnouncesNothing() {
        addUnit("als-1", ResourceType.AMBULANCE_ALS, FAR);
        openCase("p1", RiskBand.CRITICAL);
        Assignment ambulance = ambulanceSent();
        events.clear();

        assertTrue(support.sendSupportFor(ambulance).isEmpty());

        assertTrue(events.published().isEmpty());
        assertEquals(1, assignments.findActive().size());
    }

    // ---------- bad input and construction ----------

    @Test
    void onlyAMainResponseTripCanAskForAFirstResponder() {
        Assignment supportTrip = new Assignment("case-1", "nurse-1", AssignmentRole.SUPPORT, NOW);

        assertThrows(IllegalArgumentException.class, () -> support.sendSupportFor(supportTrip));
        assertThrows(IllegalArgumentException.class, () -> support.sendSupportFor(null));
    }

    @Test
    void aTripForAnUnknownCaseIsAnError() {
        Assignment orphan = new Assignment("no-such-case", "als-1", AssignmentRole.PRIMARY, NOW);

        assertThrows(IllegalStateException.class, () -> support.sendSupportFor(orphan));
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        LadderResponseDecider decider = new LadderResponseDecider();

        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(null, patients, resources, claimer, assignments, decider, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, null, resources, claimer, assignments, decider, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, null, claimer, assignments, decider, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, resources, null, assignments, decider, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, resources, claimer, null, decider, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, resources, claimer, assignments, null, events, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, resources, claimer, assignments, decider, null, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SupportDispatcher(cases, patients, resources, claimer, assignments, decider, events, null));
    }
}

package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskAssessment;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.memory.InMemoryAssignmentRepository;
import com.medidispatch.core.memory.InMemoryCaseQueue;
import com.medidispatch.core.memory.InMemoryCaseRepository;
import com.medidispatch.core.memory.InMemoryEventPublisher;
import com.medidispatch.core.memory.InMemoryFacilityRepository;
import com.medidispatch.core.memory.InMemoryResourceClaimer;
import com.medidispatch.core.memory.InMemoryResourceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starting the second leg, scene to hospital. The patient is in central Delhi; hospitals are placed a known number of
 * km due north ({@link #kmNorth}).
 */
public class TransportCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Location SCENE = new Location(28.6139, 77.2090);
    private static final Set<Capability> STROKE = Set.of(Capability.STROKE);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryCaseQueue queue = new InMemoryCaseQueue();
    private final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    private final InMemoryResourceRepository resources = new InMemoryResourceRepository();
    private final InMemoryResourceClaimer claimer = new InMemoryResourceClaimer();
    private final InMemoryAssignmentRepository assignments = new InMemoryAssignmentRepository();
    private final InMemoryFacilityRepository facilities = new InMemoryFacilityRepository();
    private final InMemoryEventPublisher events = new InMemoryEventPublisher();

    private final CaseManager caseManager = new CaseManager(cases, queue, events, clock);
    private final AssignmentManager assignmentManager =
            new AssignmentManager(assignments, resources, claimer, events, clock);
    private final TransportCoordinator transport = new TransportCoordinator(
            assignments, resources, new DestinationChooser(facilities), assignmentManager, caseManager);

    // ---------- helpers ----------

    /** An open case for p1, moved to RESPONDING the way the dispatch flow leaves it. */
    private Case respondingCase() {
        Case c = caseManager.handle(new RiskAssessment("p1", 0, RiskBand.CRITICAL, List.of(), NOW)).orElseThrow();
        queue.remove(c.id());
        c.transitionTo(CaseStatus.RESPONDING);
        cases.save(c);
        return c;
    }

    /** A unit of {@code type} sent to {@code c} in {@code role}, now ARRIVED on scene; events cleared. */
    private Assignment arrivedTrip(Case c, String unitId, ResourceType type, AssignmentRole role) {
        Resource unit = new Resource(unitId, type, SCENE);
        unit.transitionTo(ResourceStatus.ASSIGNED);
        resources.save(unit);
        claimer.tryClaim(unitId, c.id());
        Assignment trip = new Assignment(c.id(), unitId, role, NOW);
        assignments.save(trip);
        assignmentManager.onEnRoute(trip.id());
        assignmentManager.onArrived(trip.id());
        events.clear();
        return trip;
    }

    private void addHospital(String id, double kmNorth, Set<Capability> capabilities, boolean accepting) {
        facilities.save(Facility.hospital(id, "Hospital " + id, kmNorth(kmNorth), capabilities, accepting));
    }

    private Assignment trip(Assignment a) {
        return assignments.findById(a.id()).orElseThrow();
    }

    private Case reload(Case c) {
        return cases.findById(c.id()).orElseThrow();
    }

    /** 0.009 degrees of latitude is about 1 km, so this is a point roughly {@code km} north of the scene. */
    private static Location kmNorth(double km) {
        return new Location(SCENE.latitude() + km * 0.009, SCENE.longitude());
    }

    /** Asserts the trip and case were left exactly as the helpers set them up, and nothing was announced. */
    private void assertNothingChanged(Assignment a, Case c, AssignmentStatus tripWas, CaseStatus caseWas) {
        assertEquals(tripWas, trip(a).status());
        assertTrue(trip(a).hospitalId().isEmpty());
        assertEquals(caseWas, reload(c).status());
        assertTrue(events.published().isEmpty());
    }

    // ---------- the transport ----------

    /** Mrs. Sharma: the nearest hospital has no stroke unit, so the ambulance takes her to the farther one. */
    @Test
    void aStrokePatientIsTakenToTheHospitalWithAStrokeUnit() {
        addHospital("h-general", 3, Set.of(), true);
        addHospital("h-apollo", 9, STROKE, true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_ALS, AssignmentRole.PRIMARY);

        Assignment moving = transport.start(a.id(), STROKE);

        assertEquals(AssignmentStatus.TRANSPORTING, moving.status());
        assertEquals("h-apollo", trip(a).hospitalId().orElseThrow());
        assertEquals(CaseStatus.TRANSPORTING, reload(c).status());
        assertEquals(ResourceStatus.ASSIGNED, resources.findById("amb-7").orElseThrow().status());
        assertEquals(c.id(), claimer.claimedBy("amb-7").orElseThrow());
    }

    /** The case moves first, then the trip, and each move is announced once. */
    @Test
    void theCaseThenTheTripChangeIsAnnounced() {
        addHospital("h-general", 3, Set.of(), true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_BLS, AssignmentRole.PRIMARY);

        transport.start(a.id(), Set.of());

        assertEquals(List.of(
                        new CaseStatusChanged(c.id(), "p1", CaseStatus.RESPONDING, CaseStatus.TRANSPORTING, NOW),
                        new AssignmentStatusChanged(a.id(), c.id(), "amb-7",
                                AssignmentStatus.ARRIVED, AssignmentStatus.TRANSPORTING, NOW)),
                events.published());
    }

    /** The hospital is chosen from where the unit is now, not from where the trip started. */
    @Test
    void theHospitalIsChosenFromWhereTheUnitIsNow() {
        addHospital("h-south", 1, Set.of(), true);
        addHospital("h-north", 20, Set.of(), true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_ALS, AssignmentRole.PRIMARY);
        Resource unit = resources.findById("amb-7").orElseThrow();
        unit.updateLocation(kmNorth(19));                                  // the patient was met up north
        resources.save(unit);

        transport.start(a.id(), Set.of());

        assertEquals("h-north", trip(a).hospitalId().orElseThrow());
    }

    /** Handover: complete the trip, then stabilise and close the case, exactly as after treatment on scene. */
    @Test
    void afterTheHandoverTheUnitIsFreeAndTheCaseCloses() {
        addHospital("h-apollo", 9, STROKE, true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_ALS, AssignmentRole.PRIMARY);
        transport.start(a.id(), STROKE);

        assignmentManager.complete(a.id());
        caseManager.onStabilised(c.id());
        caseManager.close(c.id());

        assertEquals(AssignmentStatus.DONE, trip(a).status());
        assertEquals("h-apollo", trip(a).hospitalId().orElseThrow());
        assertEquals(ResourceStatus.AVAILABLE, resources.findById("amb-7").orElseThrow().status());
        assertTrue(claimer.claimedBy("amb-7").isEmpty());
        assertEquals(CaseStatus.CLOSED, reload(c).status());
    }

    // ---------- refusals: nothing changes ----------

    /** With nowhere to go the crew can still finish on scene, so nothing may have moved. */
    @Test
    void withNoHospitalRegisteredNothingChanges() {
        facilities.save(Facility.clinic("c1", "Lane Clinic", kmNorth(1)));    // a clinic is not a destination
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_ALS, AssignmentRole.PRIMARY);

        assertThrows(IllegalStateException.class, () -> transport.start(a.id(), STROKE));

        assertNothingChanged(a, c, AssignmentStatus.ARRIVED, CaseStatus.RESPONDING);
    }

    @Test
    void aNurseCannotCarryAPatient() {
        addHospital("h-general", 3, Set.of(), true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "nurse-1", ResourceType.NURSE, AssignmentRole.PRIMARY);

        assertThrows(IllegalStateException.class, () -> transport.start(a.id(), Set.of()));

        assertNothingChanged(a, c, AssignmentStatus.ARRIVED, CaseStatus.RESPONDING);
    }

    @Test
    void aFirstResponderCannotTransport() {
        addHospital("h-general", 3, Set.of(), true);
        Case c = respondingCase();
        Assignment a = arrivedTrip(c, "amb-2", ResourceType.AMBULANCE_BLS, AssignmentRole.SUPPORT);

        assertThrows(IllegalStateException.class, () -> transport.start(a.id(), Set.of()));

        assertNothingChanged(a, c, AssignmentStatus.ARRIVED, CaseStatus.RESPONDING);
    }

    @Test
    void aCrewThatHasNotArrivedCannotTransport() {
        addHospital("h-general", 3, Set.of(), true);
        Case c = respondingCase();
        Resource unit = new Resource("amb-7", ResourceType.AMBULANCE_ALS, SCENE);
        unit.transitionTo(ResourceStatus.ASSIGNED);
        resources.save(unit);
        Assignment a = new Assignment(c.id(), "amb-7", AssignmentRole.PRIMARY, NOW);
        assignments.save(a);
        assignmentManager.onEnRoute(a.id());
        events.clear();

        assertThrows(IllegalStateException.class, () -> transport.start(a.id(), Set.of()));

        assertNothingChanged(a, c, AssignmentStatus.EN_ROUTE, CaseStatus.RESPONDING);
    }

    /** The case is moved first because it is the step that can still fail: then the trip is left untouched. */
    @Test
    void aCaseThatIsNotRespondingIsRefusedAndTheTripIsUntouched() {
        addHospital("h-general", 3, Set.of(), true);
        Case c = caseManager.handle(new RiskAssessment("p1", 0, RiskBand.CRITICAL, List.of(), NOW)).orElseThrow();
        Assignment a = arrivedTrip(c, "amb-7", ResourceType.AMBULANCE_ALS, AssignmentRole.PRIMARY);

        assertThrows(IllegalStateException.class, () -> transport.start(a.id(), Set.of()));

        assertNothingChanged(a, c, AssignmentStatus.ARRIVED, CaseStatus.ALERTED);
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> transport.start(" ", STROKE));
        assertThrows(IllegalArgumentException.class, () -> transport.start(null, STROKE));
        assertThrows(IllegalArgumentException.class, () -> transport.start("t1", null));
        assertThrows(IllegalStateException.class, () -> transport.start("no-such-trip", STROKE));
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        DestinationChooser chooser = new DestinationChooser(facilities);
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCoordinator(null, resources, chooser, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCoordinator(assignments, null, chooser, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCoordinator(assignments, resources, null, assignmentManager, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCoordinator(assignments, resources, chooser, null, caseManager));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCoordinator(assignments, resources, chooser, assignmentManager, null));
    }
}

package com.medidispatch.service;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.engine.ProcessingResult;
import com.medidispatch.service.persistence.TestDatabase;
import com.medidispatch.service.persistence.TestDatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link DispatchService} against the real engine on the real database: the whole application, on the
 * {@code app} database of the shared test container ({@link TestDatabaseConfig}).
 *
 * <p>The tables are emptied before each test, so every test starts from an empty city. The scheduled dispatch loop
 * runs in the background too; tests still call {@link DispatchService#tick()} themselves to dispatch right now. Both
 * take the same lock, so whichever runs first sends the unit and the assertions hold either way.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class DispatchServiceTest {

    // Two points in central Delhi, under 1 km apart.
    private static final Location HOME = new Location(28.6139, 77.2090);
    private static final Location NEARBY = new Location(28.6200, 77.2150);

    @Autowired
    private DispatchService service;

    /** Every test starts from empty tables. */
    @BeforeEach
    void emptyTables() {
        TestDatabase.resetApp();
    }

    // Every vital far out of range: the scorer rates this CRITICAL.
    private static VitalsReading critical(String patientId) {
        return new VitalsReading(patientId, Instant.now(), 150, 80, 80, 50, 35, 39.5, Consciousness.UNRESPONSIVE);
    }

    /** The happy path, end to end: reading, case, dispatch, accept, arrive, complete, case closed. */
    @Test
    void aCriticalPatientIsServedFromReadingToClosedCase() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);

        ReadingResult result = service.submitReading(critical("p1"));
        assertEquals(ProcessingResult.Outcome.ACCEPTED, result.outcome());
        assertEquals(RiskBand.CRITICAL, result.openCase().band());
        String caseId = result.openCase().id();

        service.tick();
        TripInfo trip = service.activeTripOf("u1").orElseThrow();
        assertEquals(AssignmentStatus.ASSIGNED, trip.status());
        assertEquals(AssignmentRole.PRIMARY, trip.role());
        assertEquals(caseId, trip.caseId());

        assertEquals(AssignmentStatus.EN_ROUTE, service.acceptTrip(trip.id()).status());
        assertEquals(AssignmentStatus.ARRIVED, service.arrive(trip.id()).status());
        assertEquals(AssignmentStatus.DONE, service.completeTrip(trip.id()).status());

        assertEquals(CaseStatus.CLOSED, service.findCase(caseId).orElseThrow().status());
        assertEquals(ResourceStatus.AVAILABLE, service.findUnit("u1").orElseThrow().status());
        assertTrue(service.openCaseOf("p1").isEmpty());
    }

    /** Registering the same id twice must fail instead of silently replacing the first one. */
    @Test
    void anIdCanOnlyBeRegisteredOnce() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);

        assertThrows(IllegalStateException.class, () -> service.registerPatient("p1", HOME));
        assertThrows(IllegalStateException.class,
                () -> service.registerUnit("u1", ResourceType.AMBULANCE_BLS, NEARBY, null));
    }

    /** Unknown ids give NotFoundException (later a 404), and reads give an empty Optional. */
    @Test
    void unknownTripsAndUnitsAreNotFound() {
        assertThrows(NotFoundException.class, () -> service.acceptTrip("nope"));
        assertThrows(NotFoundException.class, () -> service.arrive("nope"));
        assertThrows(NotFoundException.class, () -> service.completeTrip("nope"));
        assertThrows(NotFoundException.class, () -> service.reportPosition("nope", HOME));
        assertThrows(NotFoundException.class, () -> service.backInService("nope"));

        assertTrue(service.findTrip("nope").isEmpty());
        assertTrue(service.findUnit("nope").isEmpty());
        assertTrue(service.findCase("nope").isEmpty());
    }

    /** A trip that exists but is in the wrong state gives IllegalStateException (later a 409). */
    @Test
    void arrivingBeforeAcceptingIsRefused() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p1"));
        service.tick();
        String tripId = service.activeTripOf("u1").orElseThrow().id();

        assertThrows(IllegalStateException.class, () -> service.arrive(tripId));
    }

    /**
     * A weak network can deliver the same step twice (the response was lost, the phone retried). Every repeat must
     * succeed and change nothing; in particular the case is closed once, not twice.
     */
    @Test
    void aRepeatedStepIsHarmless() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p1"));
        service.tick();
        String tripId = service.activeTripOf("u1").orElseThrow().id();

        service.acceptTrip(tripId);
        assertEquals(AssignmentStatus.EN_ROUTE, service.acceptTrip(tripId).status());
        service.arrive(tripId);
        assertEquals(AssignmentStatus.ARRIVED, service.arrive(tripId).status());
        String caseId = service.completeTrip(tripId).caseId();
        assertEquals(AssignmentStatus.DONE, service.completeTrip(tripId).status());

        assertEquals(CaseStatus.CLOSED, service.findCase(caseId).orElseThrow().status());
    }

    /** Only a repeat of the SAME step is forgiven: a late accept or arrive for a finished trip is still refused. */
    @Test
    void goingBackwardsIsStillRefused() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p1"));
        service.tick();
        String tripId = service.activeTripOf("u1").orElseThrow().id();

        service.acceptTrip(tripId);
        service.arrive(tripId);
        service.completeTrip(tripId);

        assertThrows(IllegalStateException.class, () -> service.acceptTrip(tripId));
        assertThrows(IllegalStateException.class, () -> service.arrive(tripId));
    }

    /**
     * The snapshot for live screens holds every unit (sorted by id), the open case with the patient's position,
     * and the trip under way. Both units stand at the same spot, so the nurse is not strictly closer than the
     * ambulance and no first responder is sent: exactly one trip.
     */
    @Test
    void aSnapshotShowsTheFleetTheOpenCasesAndTheTrips() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u2", ResourceType.NURSE, NEARBY, null);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p1"));
        service.tick();

        LiveSnapshot snapshot = service.snapshot();

        assertEquals(2, snapshot.units().size());
        assertEquals("u1", snapshot.units().get(0).id());
        assertEquals(1, snapshot.openCases().size());
        CaseInfo openCase = snapshot.openCases().get(0);
        assertEquals(CaseStatus.RESPONDING, openCase.status());
        assertEquals(HOME.latitude(), openCase.latitude());
        assertEquals(HOME.longitude(), openCase.longitude());
        assertEquals(1, snapshot.activeTrips().size());
        assertEquals("u1", snapshot.activeTrips().get(0).unitId());
    }

    /**
     * The lock under load: 50 threads send readings while another thread ticks. If two threads ever ran inside
     * the engine at once, a unit could be sent twice or a case lost. With 10 units for 50 critical patients,
     * exactly 10 units must be busy and exactly 10 cases RESPONDING.
     */
    @Test
    void fiftyPatientsAtOnceWhileTheLoopRuns() throws Exception {
        for (int i = 0; i < 50; i++) {
            service.registerPatient("p" + i, HOME);
        }
        for (int i = 0; i < 10; i++) {
            service.registerUnit("u" + i, ResourceType.AMBULANCE_ALS, NEARBY, null);
        }

        ExecutorService pool = Executors.newFixedThreadPool(51);
        // Every task waits at the gate, then all start together, so they really do collide.
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ReadingResult>> readings = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                String patientId = "p" + i;
                readings.add(pool.submit(() -> {
                    gate.await();
                    return service.submitReading(critical(patientId));
                }));
            }
            Future<?> ticker = pool.submit(() -> {
                gate.await();
                for (int t = 0; t < 20; t++) {
                    service.tick();
                }
                return null;
            });

            gate.countDown();
            // get() waits for each task and rethrows anything it threw, so a crash fails the test.
            for (Future<ReadingResult> reading : readings) {
                assertEquals(ProcessingResult.Outcome.ACCEPTED, reading.get().outcome());
            }
            ticker.get();
        } finally {
            pool.shutdownNow();
        }

        // The ticks may have finished before the last readings came in; one more tick serves anything left.
        service.tick();

        int busyUnits = 0;
        for (int i = 0; i < 10; i++) {
            if (service.findUnit("u" + i).orElseThrow().status() == ResourceStatus.ASSIGNED) {
                busyUnits++;
            }
        }
        int responding = 0;
        for (int i = 0; i < 50; i++) {
            if (service.openCaseOf("p" + i).orElseThrow().status() == CaseStatus.RESPONDING) {
                responding++;
            }
        }
        assertEquals(10, busyUnits);
        assertEquals(10, responding);
    }

    // ---- hospitals and clinics (H3b) ----

    /** About {@code km} due north of HOME (0.009 degrees of latitude is about 1 km). */
    private static Location kmNorth(double km) {
        return new Location(HOME.latitude() + km * 0.009, HOME.longitude());
    }

    /** p1 at HOME, ambulance u1 sent to them, accepted and arrived; returns the trip id. */
    private String arrivedTrip() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p1"));
        service.tick();
        String tripId = service.activeTripOf("u1").orElseThrow().id();
        service.acceptTrip(tripId);
        service.arrive(tripId);
        return tripId;
    }

    @Test
    void facilitiesAreRegisteredListedAndFound() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(Capability.STROKE), true));
        service.registerFacility(Facility.clinic("c-lane", "Lane Clinic", kmNorth(1)));

        List<String> ids = new ArrayList<>();
        for (FacilityInfo f : service.facilities()) {
            ids.add(f.id());
        }
        assertEquals(List.of("c-lane", "h-apollo"), ids);
        FacilityInfo apollo = service.findFacility("h-apollo").orElseThrow();
        assertEquals(FacilityKind.HOSPITAL, apollo.kind());
        assertEquals(Set.of(Capability.STROKE), apollo.capabilities());
        assertTrue(service.findFacility("nowhere").isEmpty());
    }

    /** Without the check, save() would silently replace the first facility. */
    @Test
    void aFacilityIdCanOnlyBeRegisteredOnce() {
        service.registerFacility(Facility.hospital("h1", "First", kmNorth(2), Set.of(), true));

        assertThrows(IllegalStateException.class,
                () -> service.registerFacility(Facility.clinic("h1", "Second", kmNorth(3))));
        assertEquals("First", service.findFacility("h1").orElseThrow().name());
    }

    /** Diversion on, the same again (harmless), then off; a clinic can never accept; unknown is not found. */
    @Test
    void diversionIsSwitchedOnAndOff() {
        service.registerFacility(Facility.hospital("h1", "Apollo", kmNorth(2), Set.of(), true));
        service.registerFacility(Facility.clinic("c1", "Lane Clinic", kmNorth(1)));

        assertFalse(service.setAccepting("h1", false).accepting());
        assertFalse(service.setAccepting("h1", false).accepting());
        assertFalse(service.findFacility("h1").orElseThrow().accepting());
        assertTrue(service.setAccepting("h1", true).accepting());

        assertThrows(IllegalArgumentException.class, () -> service.setAccepting("c1", true));
        assertThrows(NotFoundException.class, () -> service.setAccepting("nowhere", false));
    }

    /** A unit can belong to a facility that exists; an unknown base is refused and no unit is created. */
    @Test
    void aUnitsBaseMustExist() {
        service.registerFacility(Facility.clinic("c1", "Lane Clinic", kmNorth(1)));

        assertEquals("c1", service.registerUnit("n1", ResourceType.NURSE, NEARBY, "c1").baseId());
        assertNull(service.registerUnit("n2", ResourceType.NURSE, NEARBY, null).baseId());
        assertThrows(IllegalArgumentException.class,
                () -> service.registerUnit("n3", ResourceType.NURSE, NEARBY, "nowhere"));
        assertTrue(service.findUnit("n3").isEmpty());
    }

    /**
     * Mrs. Sharma through the service: the nearer hospital has no stroke unit, so Apollo is chosen. After the
     * handover the trip is done, the case closed and the ambulance free; the trip keeps its hospital and needs.
     */
    @Test
    void aStrokePatientIsTakenToTheHospitalWithAStrokeUnitAndTheCaseCloses() {
        service.registerFacility(Facility.hospital("h-general", "City General", kmNorth(3), Set.of(), true));
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(Capability.STROKE), true));
        String tripId = arrivedTrip();
        String caseId = service.findTrip(tripId).orElseThrow().caseId();

        TripInfo moving = service.startTransport(tripId, Set.of(Capability.STROKE));

        assertEquals(AssignmentStatus.TRANSPORTING, moving.status());
        assertEquals("h-apollo", moving.hospitalId());
        assertEquals(Set.of(Capability.STROKE), moving.needs());
        assertEquals(CaseStatus.TRANSPORTING, service.findCase(caseId).orElseThrow().status());
        assertEquals(ResourceStatus.ASSIGNED, service.findUnit("u1").orElseThrow().status());

        TripInfo done = service.completeTrip(tripId);

        assertEquals(AssignmentStatus.DONE, done.status());
        assertEquals("h-apollo", done.hospitalId());
        assertEquals(CaseStatus.CLOSED, service.findCase(caseId).orElseThrow().status());
        assertEquals(ResourceStatus.AVAILABLE, service.findUnit("u1").orElseThrow().status());
    }

    /** A retry after a lost response: same trip back, the first hospital and needs stand. */
    @Test
    void aRepeatedTransportIsHarmless() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(Capability.STROKE), true));
        String tripId = arrivedTrip();
        service.startTransport(tripId, Set.of(Capability.STROKE));

        TripInfo again = service.startTransport(tripId, Set.of(Capability.TRAUMA));

        assertEquals(AssignmentStatus.TRANSPORTING, again.status());
        assertEquals("h-apollo", again.hospitalId());
        assertEquals(Set.of(Capability.STROKE), again.needs());
    }

    /** Nowhere to go: refused, and the trip and case are exactly as before (one transaction, rolled back). */
    @Test
    void withNoHospitalTheTransportIsRefusedAndNothingChanges() {
        service.registerFacility(Facility.clinic("c1", "Lane Clinic", kmNorth(1)));
        String tripId = arrivedTrip();
        String caseId = service.findTrip(tripId).orElseThrow().caseId();

        assertThrows(IllegalStateException.class, () -> service.startTransport(tripId, Set.of()));

        TripInfo trip = service.findTrip(tripId).orElseThrow();
        assertEquals(AssignmentStatus.ARRIVED, trip.status());
        assertNull(trip.hospitalId());
        assertEquals(CaseStatus.RESPONDING, service.findCase(caseId).orElseThrow().status());
        assertThrows(NotFoundException.class, () -> service.startTransport("nope", Set.of()));
    }

    /** The live picture carries the hospitals and clinics too, with their diversion status. */
    @Test
    void theSnapshotShowsTheFacilities() {
        service.registerFacility(Facility.hospital("h1", "Apollo", kmNorth(2), Set.of(), true));
        service.setAccepting("h1", false);

        List<FacilityInfo> shown = service.snapshot().facilities();

        assertEquals(1, shown.size());
        assertEquals("h1", shown.get(0).id());
        assertFalse(shown.get(0).accepting());
    }

    // ---- the hospital's own views (H3c) ----

    /** Minutes the pre-alert should show for an ambulance at {@code from} going to {@code to}. */
    private static long expectedEta(Location from, Location to) {
        return (long) Math.ceil(from.distanceKmTo(to) / DispatchService.TRANSPORT_SPEED_KMH * 60);
    }

    /** Only the units based at the facility, sorted by id, whatever they are doing; unknown facility: not found. */
    @Test
    void aFacilitySeesOnlyItsOwnFleet() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(), true));
        service.registerFacility(Facility.clinic("c-lane", "Lane Clinic", kmNorth(1)));
        service.registerUnit("amb-2", ResourceType.AMBULANCE_BLS, NEARBY, "h-apollo");
        service.registerUnit("amb-1", ResourceType.AMBULANCE_ALS, NEARBY, "h-apollo");
        service.registerUnit("nurse-1", ResourceType.NURSE, NEARBY, "c-lane");
        service.registerUnit("free-1", ResourceType.NURSE, NEARBY, null);

        List<String> ids = new ArrayList<>();
        for (UnitInfo u : service.fleetOf("h-apollo")) {
            ids.add(u.id());
        }
        assertEquals(List.of("amb-1", "amb-2"), ids);
        assertEquals(1, service.fleetOf("c-lane").size());
        assertThrows(NotFoundException.class, () -> service.fleetOf("nowhere"));
    }

    /**
     * Mrs. Sharma's pre-alert at Apollo: band, needs, the ambulance, the ETA from where it is, her newest vitals.
     * City General (not chosen) sees nobody coming.
     */
    @Test
    void theChosenHospitalSeesThePatientComing() {
        service.registerFacility(Facility.hospital("h-general", "City General", kmNorth(3), Set.of(), true));
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(Capability.STROKE), true));
        String tripId = arrivedTrip();
        service.startTransport(tripId, Set.of(Capability.STROKE));

        List<InboundPatient> inbound = service.inboundTo("h-apollo");

        assertEquals(1, inbound.size());
        InboundPatient sharma = inbound.get(0);
        assertEquals(tripId, sharma.tripId());
        assertEquals("p1", sharma.patientId());
        assertEquals(RiskBand.CRITICAL, sharma.band());
        assertEquals(Set.of(Capability.STROKE), sharma.needs());
        assertEquals("u1", sharma.unitId());
        assertEquals(ResourceType.AMBULANCE_ALS, sharma.unitType());
        assertEquals(expectedEta(NEARBY, kmNorth(9)), sharma.etaMinutes());
        assertEquals(150, sharma.latestVitals().heartRate());
        assertTrue(service.inboundTo("h-general").isEmpty());
        assertThrows(NotFoundException.class, () -> service.inboundTo("nowhere"));
    }

    /** The ETA comes from the ambulance's live position, so it drops as the ambulance reports closer in. */
    @Test
    void theEtaDropsAsTheAmbulanceComesCloser() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(), true));
        String tripId = arrivedTrip();
        service.startTransport(tripId, Set.of());
        long before = service.inboundTo("h-apollo").get(0).etaMinutes();

        service.reportPosition("u1", kmNorth(6));

        long after = service.inboundTo("h-apollo").get(0).etaMinutes();
        assertEquals(expectedEta(kmNorth(6), kmNorth(9)), after);
        assertTrue(after < before);
    }

    /** After the handover the patient is no longer on the way in. */
    @Test
    void afterTheHandoverNobodyIsComing() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(), true));
        String tripId = arrivedTrip();
        service.startTransport(tripId, Set.of());

        service.completeTrip(tripId);

        assertTrue(service.inboundTo("h-apollo").isEmpty());
    }

    /** Two patients on the way: the one arriving sooner is listed first, whatever order they set off in. */
    @Test
    void theSoonestArrivalIsListedFirst() {
        service.registerFacility(Facility.hospital("h-apollo", "Apollo", kmNorth(9), Set.of(), true));
        String first = arrivedTrip();
        service.startTransport(first, Set.of());
        service.registerPatient("p2", HOME);
        service.registerUnit("u2", ResourceType.AMBULANCE_ALS, NEARBY, null);
        service.submitReading(critical("p2"));
        service.tick();
        String second = service.activeTripOf("u2").orElseThrow().id();
        service.acceptTrip(second);
        service.arrive(second);
        service.startTransport(second, Set.of());

        service.reportPosition("u2", kmNorth(8));                    // 1 km out; u1 is still about 8 km out

        List<InboundPatient> inbound = service.inboundTo("h-apollo");
        assertEquals(List.of(second, first), List.of(inbound.get(0).tripId(), inbound.get(1).tripId()));
    }
}

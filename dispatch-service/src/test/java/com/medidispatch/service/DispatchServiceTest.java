package com.medidispatch.service;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.Consciousness;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.core.engine.ProcessingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link DispatchService} against the real engine.
 *
 * <p>Each test builds a small Spring context with only {@link EngineConfig} and {@link DispatchService}, so every
 * test starts from an empty engine. Scheduling is not switched on here, so {@link DispatchService#tick()} runs
 * only when a test calls it: the test decides exactly when units are sent.
 */
class DispatchServiceTest {

    // Two points in central Delhi, under 1 km apart.
    private static final Location HOME = new Location(28.6139, 77.2090);
    private static final Location NEARBY = new Location(28.6200, 77.2150);

    private AnnotationConfigApplicationContext context;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(EngineConfig.class, DispatchService.class);
        service = context.getBean(DispatchService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // Every vital far out of range: the scorer rates this CRITICAL.
    private static VitalsReading critical(String patientId) {
        return new VitalsReading(patientId, Instant.now(), 150, 80, 80, 50, 35, 39.5, Consciousness.UNRESPONSIVE);
    }

    /** The happy path, end to end: reading, case, dispatch, accept, arrive, complete, case closed. */
    @Test
    void aCriticalPatientIsServedFromReadingToClosedCase() {
        service.registerPatient("p1", HOME);
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY);

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
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY);

        assertThrows(IllegalStateException.class, () -> service.registerPatient("p1", HOME));
        assertThrows(IllegalStateException.class,
                () -> service.registerUnit("u1", ResourceType.AMBULANCE_BLS, NEARBY));
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
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY);
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
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY);
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
        service.registerUnit("u1", ResourceType.AMBULANCE_ALS, NEARBY);
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
            service.registerUnit("u" + i, ResourceType.AMBULANCE_ALS, NEARBY);
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
}
